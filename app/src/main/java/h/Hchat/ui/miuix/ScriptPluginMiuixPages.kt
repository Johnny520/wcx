package h.Hchat.ui.miuix

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import h.Hchat.hooks.items.script.ScriptPluginManager
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.hooks.items.script.ScriptPluginSettings
import h.Hchat.preferences.HchatStorage
import h.Hchat.preferences.TermsGate
import h.Hchat.ui.FeatureSettingsProvider
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Composable
private fun ClickHintTag() {
    Text(
        text = "单击",
        color = MiuixTheme.colorScheme.primary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .defaultMinSize(minWidth = 40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

private object ScriptPluginSettingsMiuixContent {

@Composable
fun ScriptPluginSettingsContent(
    context: Context,
    onOpenReadme: (ScriptPluginRuntime.ScriptPlugin) -> Unit,
    onOpenMarket: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenManager: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val sp = remember { HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME) }
    val pluginRootPath = remember(context) { ScriptPluginRuntime.scriptDir(context).absolutePath }
    var pluginListVersion by remember { mutableStateOf(0) }
    val managedPlugins = remember(pluginListVersion) { ScriptPluginManager.listForDisplay(context) }
    val plugins = remember(managedPlugins) { managedPlugins.map { it.plugin } }
    val pinnedIds = remember(managedPlugins) {
        managedPlugins.filter { it.pinned }.mapTo(LinkedHashSet()) { it.plugin.id }
    }
    var globalEnabled by remember {
        mutableStateOf(sp.getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE))
    }
    var pluginEnabledStates by remember(plugins) {
        mutableStateOf(plugins.associate { it.id to ScriptPluginRuntime.isPluginEnabled(context, it.id) })
    }
    var showPluginRootDialog by remember { mutableStateOf(false) }
    var actionPlugin by remember { mutableStateOf<ScriptPluginRuntime.ScriptPlugin?>(null) }
    var renamePlugin by remember { mutableStateOf<ScriptPluginRuntime.ScriptPlugin?>(null) }
    var deletePlugin by remember { mutableStateOf<ScriptPluginRuntime.ScriptPlugin?>(null) }
    DisposableEffect(context) {
        val subscription = ScriptPluginRuntime.subscribePluginCatalog(context) {
            Handler(Looper.getMainLooper()).post {
                pluginEnabledStates = ScriptPluginRuntime.listPlugins(context)
                    .associate { it.id to ScriptPluginRuntime.isPluginEnabled(context, it.id) }
                globalEnabled = sp.getBoolean(
                    ScriptPluginSettings.KEY_ENABLE,
                    ScriptPluginSettings.DEFAULT_ENABLE
                )
                pluginListVersion++
            }
        }
        onDispose { subscription.unsubscribe() }
    }

    Column {
        SettingsCard {
            PathSwitchRow(
                globalEnabled,
                "插件总开关",
                "启动时自动加载已启用插件\n相关说明:\n请确认插件安全再进行加载,\n否则造成的后果需自行承担。",
                onInfoClick = { showPluginRootDialog = true },
            ) { next ->
                val old = globalEnabled
                globalEnabled = next
                Thread({
                    val result = ScriptPluginRuntime.setGlobalEnabled(context, next)
                    Handler(Looper.getMainLooper()).post {
                        if (result.isFailure) {
                            globalEnabled = old
                            Toast.makeText(
                                context,
                                "切换失败: ${result.exceptionOrNull()?.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            pluginListVersion++
                        }
                    }
                }, "Hchat-Script-Global").start()
            }
        }
        if (showPluginRootDialog) {
            ScriptPluginPathDialog(
                context = context,
                title = "插件目录",
                path = pluginRootPath,
                onClose = { showPluginRootDialog = false }
            )
        }
        SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
            ActionRow("插件 Agent", "按需求生成或修改脚本插件") {
                onOpenAgent()
            }
        }
        SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
            ActionRow("在线插件", "浏览、安装或上传社区脚本插件") {
                onOpenMarket()
            }
        }
        SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
            ActionRow("本地插件管理", "排序、置顶、导入、导出或批量管理插件") {
                onOpenManager()
            }
        }
        SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "本地插件(${plugins.size})")
        SettingsCard {
            if (plugins.isEmpty()) {
                Text(
                    text = "暂无插件",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                )
            } else {
                plugins.forEachIndexed { index, plugin ->
                    val checked = pluginEnabledStates[plugin.id]
                        ?: ScriptPluginRuntime.isPluginEnabled(context, plugin.id)
                    val summary = buildString {
                        append(plugin.dir.name)
                        append("\n")
                        append("作者: ")
                        append(plugin.author.ifBlank { "未知" })
                        append(" | 更新于: ")
                        append(plugin.updateTime.ifBlank { "未知" })
                    }
                    val title = buildString {
                        append(plugin.displayName ?: "未知")
                        append("(")
                        append(plugin.version.ifBlank { "未知" })
                        append(")")
                    }
                    ScriptPluginRow(
                        checked = checked,
                        title = title,
                        summary = summary,
                        showSettings = ScriptPluginRuntime.canOpenSettings(plugin),
                        onOpenReadme = { onOpenReadme(plugin) },
                        onOpenSettings = {
                            val result = ScriptPluginRuntime.callOpenSettings(plugin.id)
                            if (result.isFailure) {
                                Toast.makeText(
                                    context,
                                    result.exceptionOrNull()?.message ?: "打开设置失败",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onOpenManager = { actionPlugin = plugin },
                        onCheckedChange = { next ->
                            val oldMap = pluginEnabledStates
                            pluginEnabledStates = pluginEnabledStates + (plugin.id to next)
                            Thread({
                                val result = ScriptPluginRuntime.setPluginEnabled(context, plugin.id, next)
                                Handler(Looper.getMainLooper()).post {
                                    if (result.isFailure) {
                                        pluginEnabledStates = oldMap
                                        Toast.makeText(
                                            context,
                                            "加载[${plugin.displayName ?: "未知"}]失败，已自动关闭",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }, "Hchat-Script-${plugin.id}").start()
                        }
                    )
                    if (index != plugins.lastIndex) InsetDivider()
                }
            }
        }
        actionPlugin?.let { plugin ->
            ScriptPluginActionDialog(
                plugin = plugin,
                pinned = plugin.id in pinnedIds,
                onDismiss = { actionPlugin = null },
                onPinChanged = { pinned ->
                    actionPlugin = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ScriptPluginManager.setPinned(context, listOf(plugin.id), pinned)
                        }
                        result.fold(
                            onSuccess = {
                                pluginListVersion++
                                Toast.makeText(
                                    context,
                                    if (pinned) "已置顶" else "已取消置顶",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onFailure = { showScriptPluginManagerError(context, it) }
                        )
                    }
                },
                onRename = {
                    actionPlugin = null
                    showAfterDialogDismiss(context) { renamePlugin = plugin }
                },
                onExport = {
                    actionPlugin = null
                    showAfterDialogDismiss(context) {
                        launchScriptPluginExport(
                            context = context,
                            plugins = listOf(plugin)
                        )
                    }
                },
                onDelete = {
                    actionPlugin = null
                    showAfterDialogDismiss(context) { deletePlugin = plugin }
                }
            )
        }
        renamePlugin?.let { plugin ->
            ScriptPluginRenameDialog(
                plugin = plugin,
                onDismiss = { renamePlugin = null },
                onConfirm = { name ->
                    renamePlugin = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ScriptPluginManager.renamePlugin(context, plugin.id, name)
                        }
                        result.fold(
                            onSuccess = {
                                pluginListVersion++
                                Toast.makeText(context, "已重命名", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { showScriptPluginManagerError(context, it) }
                        )
                    }
                }
            )
        }
        deletePlugin?.let { plugin ->
            ScriptPluginDeleteDialog(
                pluginNames = listOf(plugin.displayName ?: plugin.name.ifBlank { plugin.id }),
                onDismiss = { deletePlugin = null },
                onConfirm = {
                    deletePlugin = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ScriptPluginManager.deletePlugin(context, plugin.id)
                        }
                        result.fold(
                            onSuccess = {
                                pluginListVersion++
                                Toast.makeText(context, "插件已删除", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { showScriptPluginManagerError(context, it) }
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun ScriptPluginManagerPage(
    context: Context,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    var query by rememberSaveable { mutableStateOf("") }
    var refreshVersion by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var managedPlugins by remember {
        mutableStateOf<List<ScriptPluginManager.ManagedPlugin>>(emptyList())
    }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var draggedPluginId by remember { mutableStateOf<String?>(null) }
    var dragOrderChanged by remember { mutableStateOf(false) }
    var actionPlugin by remember { mutableStateOf<ScriptPluginManager.ManagedPlugin?>(null) }
    var renamePlugin by remember { mutableStateOf<ScriptPluginRuntime.ScriptPlugin?>(null) }
    var deletePlugins by remember { mutableStateOf<List<ScriptPluginRuntime.ScriptPlugin>>(emptyList()) }
    var importInspection by remember { mutableStateOf<ScriptPluginManager.ImportInspection?>(null) }
    var importOverwriteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busyText by remember { mutableStateOf("") }
    val pageActive = remember { AtomicBoolean(true) }
    val activeImportSessionId = remember { AtomicReference<String?>(null) }

    LaunchedEffect(refreshVersion) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching {
                ScriptPluginManager.cleanupStaleDisplayIds(context).getOrThrow()
                ScriptPluginManager.listForDisplay(context)
            }
        }
        result.fold(
            onSuccess = { plugins ->
                managedPlugins = plugins
                selectedIds = selectedIds.intersect(plugins.mapTo(LinkedHashSet()) { it.plugin.id })
            },
            onFailure = { showScriptPluginManagerError(context, it) }
        )
        loading = false
    }
    DisposableEffect(context) {
        val subscription = ScriptPluginRuntime.subscribePluginCatalog(context) {
            Handler(Looper.getMainLooper()).post { refreshVersion++ }
        }
        onDispose { subscription.unsubscribe() }
    }
    DisposableEffect(Unit) {
        pageActive.set(true)
        onDispose {
            pageActive.set(false)
            activeImportSessionId.getAndSet(null)?.let { sessionId ->
                Thread({ ScriptPluginManager.discardImport(context, sessionId) }, "Hchat-Plugin-Import-Cleanup").start()
            }
        }
    }

    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val visiblePlugins = managedPlugins.filter { item ->
        val plugin = item.plugin
        normalizedQuery.isEmpty() ||
            plugin.id.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            plugin.name.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            plugin.author.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            plugin.version.lowercase(Locale.ROOT).contains(normalizedQuery)
    }
    val pinnedPlugins = visiblePlugins.filter { it.pinned }
    val normalPlugins = visiblePlugins.filterNot { it.pinned }
    val visibleIds = visiblePlugins.mapTo(LinkedHashSet()) { it.plugin.id }
    val allVisibleSelected = visibleIds.isNotEmpty() && visibleIds.all { it in selectedIds }

    fun refresh() {
        refreshVersion++
    }

    fun setPinned(ids: Set<String>, pinned: Boolean) {
        if (ids.isEmpty() || busyText.isNotEmpty()) return
        busyText = if (pinned) "正在置顶" else "正在取消置顶"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ScriptPluginManager.setPinned(context, ids, pinned)
            }
            busyText = ""
            result.fold(
                onSuccess = {
                    refresh()
                    Toast.makeText(
                        context,
                        if (pinned) "已置顶 ${ids.size} 个插件" else "已取消置顶 ${ids.size} 个插件",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = { showScriptPluginManagerError(context, it) }
            )
        }
    }

    fun commitOrder() {
        val ids = managedPlugins.map { it.plugin.id }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ScriptPluginManager.saveDisplayOrder(context, ids)
            }
            result.onFailure { showScriptPluginManagerError(context, it) }
        }
    }

    fun movePlugin(item: ScriptPluginManager.ManagedPlugin, delta: Int): Boolean {
        if (query.isNotBlank()) return false
        val index = managedPlugins.indexOfFirst { it.plugin.id == item.plugin.id }
        val targetIndex = index + delta
        if (index < 0 || targetIndex !in managedPlugins.indices) return false
        if (managedPlugins[targetIndex].pinned != item.pinned) return false
        val next = managedPlugins.toMutableList()
        val moved = next.removeAt(index)
        next.add(targetIndex, moved)
        managedPlugins = next
        return true
    }

    fun launchImportPicker() {
        val activity = context as? Activity
        if (activity == null) {
            Toast.makeText(context, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
            return
        }
        ScriptPluginDocumentBridge.launchImport(activity) { uri ->
            if (!pageActive.get()) return@launchImport
            busyText = "正在检查导入包"
            Thread({
                val result = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        ScriptPluginManager.inspectImport(context, input).getOrThrow()
                    } ?: error("无法读取所选文件")
                }
                if (!pageActive.get()) {
                    result.getOrNull()?.let { inspection ->
                        ScriptPluginManager.discardImport(context, inspection.sessionId)
                    }
                    return@Thread
                }
                Handler(Looper.getMainLooper()).post {
                    if (!pageActive.get()) {
                        result.getOrNull()?.let { inspection ->
                            Thread(
                                { ScriptPluginManager.discardImport(context, inspection.sessionId) },
                                "Hchat-Plugin-Import-Late-Cleanup"
                            ).start()
                        }
                        return@post
                    }
                    busyText = ""
                    result.fold(
                        onSuccess = { inspection ->
                            activeImportSessionId.set(inspection.sessionId)
                            importInspection = inspection
                            importOverwriteIds = emptySet()
                        },
                        onFailure = { showScriptPluginManagerError(context, it) }
                    )
                }
            }, "Hchat-Plugin-Import-Inspect").start()
        }
    }

    PageScaffold(
        title = "本地插件管理",
        largeTitle = "本地插件管理",
        scrollBehavior = scrollBehavior,
        onBack = onBack,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item {
                SearchBarSurface(
                    query = query,
                    placeholder = "搜索本地插件",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    onQueryChange = { query = it }
                )
            }
            item { SmallTitle(modifier = Modifier.padding(top = 6.dp), text = "管理") }
            item {
                SettingsCard {
                    ScriptPluginManagerCommandRow(
                        icon = NavIcons.Import,
                        title = "导入插件",
                        summary = "从 ZIP 文件导入，导入后默认关闭",
                        enabled = busyText.isEmpty(),
                        onClick = ::launchImportPicker
                    )
                    InsetDivider()
                    ScriptPluginSelectionToolbar(
                        selectedCount = selectedIds.size,
                        allVisibleSelected = allVisibleSelected,
                        enabled = visibleIds.isNotEmpty() && busyText.isEmpty(),
                        onSelectAll = {
                            selectedIds = if (allVisibleSelected) {
                                selectedIds - visibleIds
                            } else {
                                selectedIds + visibleIds
                            }
                        },
                        onInvert = {
                            selectedIds = (selectedIds - visibleIds) + (visibleIds - selectedIds)
                        }
                    )
                    if (selectedIds.isNotEmpty()) {
                        InsetDivider()
                        ScriptPluginBatchActions(
                            enabled = busyText.isEmpty(),
                            onPin = { setPinned(selectedIds, true) },
                            onUnpin = { setPinned(selectedIds, false) },
                            onExport = {
                                val selected = managedPlugins.filter { it.plugin.id in selectedIds }.map { it.plugin }
                                launchScriptPluginExport(context, selected)
                            },
                            onDelete = {
                                deletePlugins = managedPlugins
                                    .filter { it.plugin.id in selectedIds }
                                    .map { it.plugin }
                            }
                        )
                    }
                }
            }
            if (busyText.isNotEmpty()) {
                item {
                    Text(
                        text = busyText,
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }
            when {
                loading -> item { EmptyText("正在加载插件") }
                managedPlugins.isEmpty() -> item { EmptyText("暂无本地插件") }
                visiblePlugins.isEmpty() -> item { EmptyText("没有匹配的插件") }
                else -> {
                    if (pinnedPlugins.isNotEmpty()) {
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "已置顶(${pinnedPlugins.size})") }
                        item {
                            SettingsCard {
                                pinnedPlugins.forEachIndexed { index, item ->
                                    key(item.plugin.id) {
                                        ScriptPluginManagerRow(
                                            modifier = Modifier,
                                            item = item,
                                            selected = item.plugin.id in selectedIds,
                                            dragEnabled = query.isBlank() && busyText.isEmpty(),
                                            onSelectedChange = { selected ->
                                                selectedIds = if (selected) {
                                                    selectedIds + item.plugin.id
                                                } else {
                                                    selectedIds - item.plugin.id
                                                }
                                            },
                                            onOpenActions = { actionPlugin = item },
                                            onDragMove = { delta -> movePlugin(item, delta) },
                                            onDragStateChange = { dragging ->
                                                if (dragging) {
                                                    draggedPluginId = item.plugin.id
                                                    dragOrderChanged = false
                                                } else if (draggedPluginId == item.plugin.id) {
                                                    draggedPluginId = null
                                                    if (dragOrderChanged) commitOrder()
                                                }
                                            },
                                            onOrderChanged = { dragOrderChanged = true }
                                        )
                                        if (index != pinnedPlugins.lastIndex) InsetDivider()
                                    }
                                }
                            }
                        }
                    }
                    if (normalPlugins.isNotEmpty()) {
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "其他插件(${normalPlugins.size})") }
                        item {
                            SettingsCard {
                                normalPlugins.forEachIndexed { index, item ->
                                    key(item.plugin.id) {
                                        ScriptPluginManagerRow(
                                            modifier = Modifier,
                                            item = item,
                                            selected = item.plugin.id in selectedIds,
                                            dragEnabled = query.isBlank() && busyText.isEmpty(),
                                            onSelectedChange = { selected ->
                                                selectedIds = if (selected) {
                                                    selectedIds + item.plugin.id
                                                } else {
                                                    selectedIds - item.plugin.id
                                                }
                                            },
                                            onOpenActions = { actionPlugin = item },
                                            onDragMove = { delta -> movePlugin(item, delta) },
                                            onDragStateChange = { dragging ->
                                                if (dragging) {
                                                    draggedPluginId = item.plugin.id
                                                    dragOrderChanged = false
                                                } else if (draggedPluginId == item.plugin.id) {
                                                    draggedPluginId = null
                                                    if (dragOrderChanged) commitOrder()
                                                }
                                            },
                                            onOrderChanged = { dragOrderChanged = true }
                                        )
                                        if (index != normalPlugins.lastIndex) InsetDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    actionPlugin?.let { item ->
        ScriptPluginActionDialog(
            plugin = item.plugin,
            pinned = item.pinned,
            onDismiss = { actionPlugin = null },
            onPinChanged = { pinned ->
                actionPlugin = null
                setPinned(setOf(item.plugin.id), pinned)
            },
            onRename = {
                actionPlugin = null
                showAfterDialogDismiss(context) { renamePlugin = item.plugin }
            },
            onExport = {
                actionPlugin = null
                showAfterDialogDismiss(context) {
                    launchScriptPluginExport(context, listOf(item.plugin))
                }
            },
            onDelete = {
                actionPlugin = null
                showAfterDialogDismiss(context) { deletePlugins = listOf(item.plugin) }
            }
        )
    }
    renamePlugin?.let { plugin ->
        ScriptPluginRenameDialog(
            plugin = plugin,
            onDismiss = { renamePlugin = null },
            onConfirm = { name ->
                renamePlugin = null
                busyText = "正在重命名"
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        ScriptPluginManager.renamePlugin(context, plugin.id, name)
                    }
                    busyText = ""
                    result.fold(
                        onSuccess = {
                            refresh()
                            Toast.makeText(context, "已重命名", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { showScriptPluginManagerError(context, it) }
                    )
                }
            }
        )
    }
    if (deletePlugins.isNotEmpty()) {
        val pendingDelete = deletePlugins
        ScriptPluginDeleteDialog(
            pluginNames = pendingDelete.map { it.displayName ?: it.name.ifBlank { it.id } },
            onDismiss = { deletePlugins = emptyList() },
            onConfirm = {
                deletePlugins = emptyList()
                busyText = "正在删除插件"
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        ScriptPluginManager.deletePlugins(context, pendingDelete.map { it.id })
                    }
                    busyText = ""
                    result.fold(
                        onSuccess = {
                            selectedIds = selectedIds - pendingDelete.mapTo(LinkedHashSet()) { it.id }
                            refresh()
                            Toast.makeText(context, "已删除 ${pendingDelete.size} 个插件", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { showScriptPluginManagerError(context, it) }
                    )
                }
            }
        )
    }
    importInspection?.let { inspection ->
        ScriptPluginImportDialog(
            inspection = inspection,
            overwriteIds = importOverwriteIds,
            applying = busyText == "正在导入插件",
            onOverwriteChanged = { pluginId, overwrite ->
                importOverwriteIds = if (overwrite) {
                    importOverwriteIds + pluginId
                } else {
                    importOverwriteIds - pluginId
                }
            },
            onDismiss = {
                activeImportSessionId.set(null)
                importInspection = null
                Thread(
                    { ScriptPluginManager.discardImport(context, inspection.sessionId) },
                    "Hchat-Plugin-Import-Discard"
                ).start()
            },
            onConfirm = {
                busyText = "正在导入插件"
                scope.launch {
                    val actions = inspection.conflicts.associateWith { pluginId ->
                        if (pluginId in importOverwriteIds) {
                            ScriptPluginManager.ImportConflictAction.OVERWRITE
                        } else {
                            ScriptPluginManager.ImportConflictAction.SKIP
                        }
                    }
                    val result = withContext(Dispatchers.IO) {
                        ScriptPluginManager.applyImport(context, inspection.sessionId, actions)
                    }
                    busyText = ""
                    result.fold(
                        onSuccess = { imported ->
                            activeImportSessionId.set(null)
                            importInspection = null
                            importOverwriteIds = emptySet()
                            refresh()
                            Toast.makeText(
                                context,
                                "已导入 ${imported.importedPluginIds.size} 个插件，保持关闭状态",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        onFailure = { showScriptPluginManagerError(context, it) }
                    )
                }
            }
        )
    }
}

@Composable
private fun ScriptPluginSelectionToolbar(
    selectedCount: Int,
    allVisibleSelected: Boolean,
    enabled: Boolean,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (selectedCount == 0) "未选择插件" else "已选择 $selectedCount 个",
            color = MiuixTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            text = if (allVisibleSelected) "取消全选" else "全选",
            enabled = enabled,
            onClick = onSelectAll,
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
        TextButton(
            text = "反选",
            enabled = enabled,
            onClick = onInvert,
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
    }
}

@Composable
private fun ScriptPluginBatchActions(
    enabled: Boolean,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ScriptPluginCompactAction("置顶", enabled, Modifier.weight(1f), onPin)
            ScriptPluginCompactAction("取消置顶", enabled, Modifier.weight(1f), onUnpin)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ScriptPluginCompactAction("导出", enabled, Modifier.weight(1f), onExport)
            ScriptPluginCompactAction("删除", enabled, Modifier.weight(1f), onDelete, destructive = true)
        }
    }
}

@Composable
private fun ScriptPluginCompactAction(
    text: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    val color = when {
        !enabled -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
        destructive -> Color(0xFFD93025)
        else -> MiuixTheme.colorScheme.primary
    }
    Box(
        modifier = modifier.height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.10f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ScriptPluginManagerCommandRow(
    icon: ImageVector,
    title: String,
    summary: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val color = if (enabled) MiuixTheme.colorScheme.onSurface else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(text = title, color = color, fontWeight = FontWeight.Medium)
            Text(text = summary, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ScriptPluginManagerRow(
    modifier: Modifier,
    item: ScriptPluginManager.ManagedPlugin,
    selected: Boolean,
    dragEnabled: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onOpenActions: () -> Unit,
    onDragMove: (Int) -> Boolean,
    onDragStateChange: (Boolean) -> Unit,
    onOrderChanged: () -> Unit
) {
    val plugin = item.plugin
    val density = LocalDensity.current
    var itemHeightPx by remember(plugin.id) { mutableStateOf(0f) }
    var dragDistanceY by remember(plugin.id) { mutableStateOf(0f) }
    var dragging by remember(plugin.id) { mutableStateOf(false) }
    val currentOnDragMove by rememberUpdatedState(onDragMove)
    val currentOnDragStateChange by rememberUpdatedState(onDragStateChange)
    val currentOnOrderChanged by rememberUpdatedState(onOrderChanged)
    val visualDragY by animateFloatAsState(
        targetValue = dragDistanceY,
        animationSpec = tween(durationMillis = if (dragging) 0 else 160),
        label = "ScriptPluginDragOffset"
    )
    Row(
        modifier = modifier.zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = visualDragY
                shadowElevation = if (dragging) with(density) { 7.dp.toPx() } else 0f
                shape = RoundedCornerShape(8.dp)
            }
            .fillMaxWidth()
            .onSizeChanged { itemHeightPx = it.height.toFloat() }
            .clickable { onSelectedChange(!selected) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectionMark(selected = selected, multiSelect = true)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = plugin.displayName ?: plugin.name.ifBlank { plugin.id },
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (item.pinned) {
                    Image(
                        imageVector = NavIcons.Pin,
                        contentDescription = "已置顶",
                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary),
                        modifier = Modifier.padding(start = 6.dp).size(16.dp)
                    )
                }
            }
            Text(
                text = buildString {
                    append(plugin.id)
                    append(" · ").append(plugin.author.ifBlank { "未知作者" })
                    append(" · ").append(plugin.version.ifBlank { "未知版本" })
                },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Image(
            imageVector = NavIcons.More,
            contentDescription = "管理插件",
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
            modifier = Modifier.padding(start = 8.dp).size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    onOpenActions()
                }
                .padding(5.dp)
        )
        Image(
            imageVector = NavIcons.Drag,
            contentDescription = if (dragEnabled) "长按拖动排序" else "搜索时不可排序",
            colorFilter = ColorFilter.tint(
                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = if (dragEnabled) 1f else 0.35f)
            ),
            modifier = Modifier.padding(start = 4.dp).size(32.dp)
                .pointerInput(plugin.id, dragEnabled, itemHeightPx) {
                    if (!dragEnabled) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            dragDistanceY = 0f
                            dragging = true
                            currentOnDragStateChange(true)
                        },
                        onDragEnd = {
                            dragging = false
                            dragDistanceY = 0f
                            currentOnDragStateChange(false)
                        },
                        onDragCancel = {
                            dragging = false
                            dragDistanceY = 0f
                            currentOnDragStateChange(false)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragDistanceY += dragAmount.y
                            val step = itemHeightPx.takeIf { it > 0f } ?: with(density) { 62.dp.toPx() }
                            while (dragDistanceY <= -step * 0.5f) {
                                if (currentOnDragMove(-1)) {
                                    dragDistanceY += step
                                    currentOnOrderChanged()
                                } else {
                                    dragDistanceY = -step * 0.45f
                                    break
                                }
                            }
                            while (dragDistanceY >= step * 0.5f) {
                                if (currentOnDragMove(1)) {
                                    dragDistanceY -= step
                                    currentOnOrderChanged()
                                } else {
                                    dragDistanceY = step * 0.45f
                                    break
                                }
                            }
                        }
                    )
                }
                .padding(5.dp)
        )
    }
}

@Composable
private fun ScriptPluginActionDialog(
    plugin: ScriptPluginRuntime.ScriptPlugin,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onPinChanged: (Boolean) -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    WindowDialog(
        show = true,
        title = plugin.displayName ?: plugin.name.ifBlank { plugin.id },
        onDismissRequest = onDismiss,
        content = {
            Column {
                ScriptPluginDialogActionRow(
                    icon = NavIcons.Pin,
                    text = if (pinned) "取消置顶" else "置顶",
                    onClick = { onPinChanged(!pinned) }
                )
                ScriptPluginDialogActionRow(NavIcons.Edit, "重命名", onClick = onRename)
                ScriptPluginDialogActionRow(NavIcons.Export, "导出", onClick = onExport)
                ScriptPluginDialogActionRow(NavIcons.Delete, "删除", destructive = true, onClick = onDelete)
            }
        }
    )
}

@Composable
private fun ScriptPluginDialogActionRow(
    icon: ImageVector,
    text: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val color = if (destructive) Color(0xFFD93025) else MiuixTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (destructive) Color(0xFFD93025).copy(alpha = 0.08f)
                else MiuixTheme.colorScheme.secondaryVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = Modifier.size(22.dp)
        )
        Text(text = text, color = color, fontSize = 15.sp, modifier = Modifier.padding(start = 18.dp))
    }
}

@Composable
private fun ScriptPluginRenameDialog(
    plugin: ScriptPluginRuntime.ScriptPlugin,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(plugin.id) {
        mutableStateOf(plugin.displayName ?: plugin.name.ifBlank { plugin.id })
    }
    WindowDialog(
        show = true,
        title = "重命名插件",
        onDismissRequest = onDismiss,
        content = {
            Column {
                Text(
                    text = "仅修改展示名称，插件目录和 ID 保持不变",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp
                )
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    singleLine = true,
                    textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.secondaryVariant)
                        .padding(horizontal = 12.dp, vertical = 11.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = "确定",
                        onClick = { onConfirm(name.trim()) },
                        enabled = name.trim().isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

@Composable
private fun ScriptPluginDeleteDialog(
    pluginNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    WindowDialog(
        show = true,
        title = if (pluginNames.size == 1) "删除插件" else "批量删除插件",
        onDismissRequest = onDismiss,
        content = {
            Column {
                Text(
                    text = if (pluginNames.size == 1) {
                        "确定删除“${pluginNames.first()}”吗？插件会先停止运行，删除后无法恢复。"
                    } else {
                        "确定删除已选的 ${pluginNames.size} 个插件吗？插件会先停止运行，删除后无法恢复。"
                    },
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = "删除",
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

@Composable
private fun ScriptPluginImportDialog(
    inspection: ScriptPluginManager.ImportInspection,
    overwriteIds: Set<String>,
    applying: Boolean,
    onOverwriteChanged: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    WindowDialog(
        show = true,
        title = "导入插件",
        onDismissRequest = { if (!applying) onDismiss() },
        content = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                Text(
                    text = "共 ${inspection.plugins.size} 个插件。已有同 ID 插件默认跳过，可单独选择覆盖。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).padding(top = 10.dp)
                ) {
                    items(inspection.plugins, key = { it.pluginId }) { plugin ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !applying && plugin.conflict) {
                                    onOverwriteChanged(plugin.pluginId, plugin.pluginId !in overwriteIds)
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = plugin.name.ifBlank { plugin.pluginId },
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (plugin.conflict) {
                                        if (plugin.pluginId in overwriteIds) "覆盖已有插件" else "跳过已有插件"
                                    } else {
                                        "新增插件"
                                    },
                                    color = if (plugin.conflict && plugin.pluginId in overwriteIds) {
                                        Color(0xFFD93025)
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                    fontSize = 12.sp
                                )
                            }
                            if (plugin.conflict) {
                                SelectionMark(
                                    selected = plugin.pluginId in overwriteIds,
                                    multiSelect = true
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        enabled = !applying,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = if (applying) "正在导入" else "导入",
                        onClick = onConfirm,
                        enabled = !applying,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

private fun launchScriptPluginExport(
    context: Context,
    plugins: List<ScriptPluginRuntime.ScriptPlugin>
) {
    if (plugins.isEmpty()) {
        Toast.makeText(context, "未选择插件", Toast.LENGTH_SHORT).show()
        return
    }
    val activity = context as? Activity
    if (activity == null) {
        Toast.makeText(context, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        return
    }
    val fileName = scriptPluginExportFileName(plugins)
    ScriptPluginDocumentBridge.launchExport(activity, fileName) { uri ->
        Thread({
            val result = runCatching {
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    ScriptPluginManager.exportPlugins(context, plugins.map { it.id }, output).getOrThrow()
                } ?: error("无法写入所选文件")
            }
            if (result.isFailure) runCatching { context.contentResolver.delete(uri, null, null) }
            Handler(Looper.getMainLooper()).post {
                result.fold(
                    onSuccess = { exported ->
                        Toast.makeText(
                            context,
                            "已导出 ${exported.exportedPluginIds.size} 个插件",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { showScriptPluginManagerError(context, it) }
                )
            }
        }, "Hchat-Plugin-Export").start()
    }
}

private fun scriptPluginExportFileName(plugins: List<ScriptPluginRuntime.ScriptPlugin>): String {
    val base = if (plugins.size == 1) {
        plugins.first().displayName ?: plugins.first().name.ifBlank { plugins.first().id }
    } else {
        "Hchat_脚本插件_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
    }
    val safe = base.removeSuffix(".zip")
        .replace(Regex("""[\\/:*?\"<>|]"""), "_")
        .trim()
        .ifBlank { "Hchat_脚本插件" }
    return "$safe.zip"
}

private fun showAfterDialogDismiss(context: Context, action: () -> Unit) {
    val activity = context as? Activity
    if (activity != null) {
        activity.window.decorView.postOnAnimation(action)
    } else {
        Handler(Looper.getMainLooper()).post(action)
    }
}

private fun showScriptPluginManagerError(context: Context, error: Throwable) {
    h.Hchat.utils.HLog.e("[Hchat:ScriptManager] ${error.message ?: "操作失败"}", error)
    Toast.makeText(context, error.message ?: "操作失败", Toast.LENGTH_LONG).show()
}

@Composable
private fun PathSwitchRow(
    checked: Boolean,
    title: String,
    summary: String,
    onInfoClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val pressFeedbackColor = rememberPressFeedbackColor(pressed)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(pressFeedbackColor)
                .responsiveTap(
                    onClick = onInfoClick,
                    onPressedChange = { pressed = it }
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                ClickHintTag()
            }
            Text(text = summary, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun ScriptPluginRow(
    checked: Boolean,
    title: String,
    summary: String,
    showSettings: Boolean,
    onOpenReadme: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenManager: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val pressFeedbackColor = rememberPressFeedbackColor(pressed)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pressFeedbackColor)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
                .responsiveTap(
                    onClick = onOpenReadme,
                    onPressedChange = { pressed = it }
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                ClickHintTag()
            }
            Text(text = summary, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
        }
        if (showSettings) {
            Image(
                imageVector = NavIcons.Settings,
                contentDescription = "插件设置",
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
                modifier = Modifier
                    .padding(start = 10.dp, end = 8.dp)
                    .size(22.dp)
                    .responsiveTap(onClick = onOpenSettings)
            )
        }
        Image(
            imageVector = NavIcons.More,
            contentDescription = "管理插件",
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
            modifier = Modifier
                .padding(start = if (showSettings) 0.dp else 10.dp, end = 8.dp)
                .size(22.dp)
                .responsiveTap(onClick = onOpenManager)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ScriptPluginPathDialog(
    context: Context,
    title: String,
    path: String,
    onClose: () -> Unit
) {
    WindowDialog(
        show = true,
        title = title,
        onDismissRequest = onClose,
        content = {
            Column {
                Text(
                    text = path,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
                TextButton(
                    text = "复制路径",
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("HchatScriptDir", path))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                TextButton(
                    text = "关闭",
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

@Composable
fun FirstUseAgreementDialog(
    context: Context,
    onCancel: () -> Unit,
    onAccepted: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var remainingSeconds by remember { mutableStateOf(30) }
    val acceptedText = TermsGate.AGREEMENT_TEXT
    LaunchedEffect(Unit) {
        while (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds -= 1
        }
    }
    val agreement = remember {
        """
        Hchat 使用协议与免责声明

        1. 本模块为免费模块，仅供个人学习、研究、测试、逆向分析和备份用途使用，不提供任何商业授权、售后承诺或稳定性保证。
        2. 本模块与微信、腾讯及其关联主体无关，不代表官方立场，也不是官方客户端、官方插件或官方服务的一部分。
        3. 禁止倒卖、付费分发、捆绑销售、二次打包收费、引流售卖、以捐赠名义收费，禁止冒充作者、官方渠道或授权代理发布。
        4. 请勿在国内公开平台、群组、论坛、短视频平台、网盘分享页、应用市场或其它公开渠道传播、推广、引流、售卖或组织分发本模块。
        5. 本模块可能会修改微信运行时行为，使用后可能出现功能异常、消息异常、账号风控、限制登录、数据异常、闪退、掉线、模块冲突或其它不可预期问题。
        6. 使用者应自行确认所在地法律法规、平台协议、设备环境和账号风险；因安装、使用、传播、修改、二次分发或与其它模块共存产生的任何后果均由使用者自行承担。
        7. 禁止将本模块用于骚扰、欺诈、刷量、营销轰炸、盗取信息、破坏服务稳定性、绕过平台风控、侵犯他人权益或其它违法违规用途。
        8. 本模块不保证适配所有微信版本、系统版本、设备环境、热更新状态和其它模块共存环境，也不承诺持续维护、及时修复或提供任何形式的服务保障。
        9. 如果你不同意以上任一条款，请点击取消并停止使用本模块。

        如果你理解并接受以上内容，请在下方输入“$acceptedText”后继续使用。
        """.trimIndent()
    }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val dialogMaxHeight = (screenHeight * 0.78f).coerceAtMost(640.dp)
    val agreementMaxHeight = (screenHeight * 0.42f).coerceIn(220.dp, 430.dp)
    WindowDialog(
        show = true,
        title = "使用协议",
        onDismissRequest = {},
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = dialogMaxHeight)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = agreementMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = agreement,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }
                Text(
                    text = "请输入“$acceptedText”确认",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 14.dp)
                )
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.secondaryVariant)
                        .padding(horizontal = 12.dp, vertical = 11.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = if (remainingSeconds > 0) "继续（${remainingSeconds}s）" else "同意并继续",
                        onClick = {
                            if (remainingSeconds > 0) {
                                Toast.makeText(context, "请等待 ${remainingSeconds} 秒后继续", Toast.LENGTH_SHORT).show()
                            } else if (input.trim() == acceptedText) {
                                if (TermsGate.accept(context)) {
                                    onAccepted()
                                } else {
                                    Toast.makeText(context, "协议状态保存失败，请重试", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "请输入“$acceptedText”后继续", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

@Composable
fun ScriptPluginReadmeDialog(
    context: Context,
    plugin: ScriptPluginRuntime.ScriptPlugin,
    onClose: () -> Unit
) {
    val readme = remember(plugin.id) {
        runCatching {
            val file = File(plugin.dir, "README.md")
            if (file.isFile) file.readText(Charsets.UTF_8) else ""
        }.getOrDefault("")
    }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val dialogMaxHeight = (screenHeight * 0.78f).coerceAtMost(640.dp)
    val readmeMaxHeight = (screenHeight * 0.58f).coerceIn(240.dp, 520.dp)

    WindowDialog(
        show = true,
        title = plugin.displayName ?: "未知",
        onDismissRequest = onClose,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = dialogMaxHeight)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = readmeMaxHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (readme.isBlank()) {
                        Text(
                            text = "暂无说明",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    } else {
                        MarkdownUi.Content(context, readme)
                    }
                }
                TextButton(
                    text = "关闭",
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

}

private object MarkdownUi {

@Composable
fun Content(
    context: Context,
    markdown: String,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    bodyFontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    onCopyCode: ((String) -> Unit)? = null
) {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
    var inCodeBlock = false
    var inlineState = MarkdownInlineState()
    val codeLines = ArrayList<String>()
    Column(modifier = Modifier.fillMaxWidth().padding(contentPadding)) {
        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    CodeBlock(codeLines.joinToString("\n"), onCopyCode)
                    codeLines.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                    codeLines.clear()
                }
                return@forEach
            }
            if (inCodeBlock) {
                codeLines += rawLine
                return@forEach
            }
            val nextState = MarkdownLine(context, line, inlineState, bodyFontSize)
            inlineState = nextState
        }
        if (inCodeBlock && codeLines.isNotEmpty()) {
            CodeBlock(codeLines.joinToString("\n"), onCopyCode)
        }
    }
}

@Composable
private fun MarkdownLine(
    context: Context,
    line: String,
    inlineState: MarkdownInlineState,
    bodyFontSize: androidx.compose.ui.unit.TextUnit = 13.sp
): MarkdownInlineState {
    val trimmed = line.trim()
    return when {
        trimmed.isBlank() -> {
            Box(modifier = Modifier.height(8.dp))
            inlineState
        }
        trimmed.matches(Regex("""-{3,}|_{3,}|\*{3,}""")) -> {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(1.dp)
                    .background(MiuixTheme.colorScheme.outline)
            )
            inlineState
        }
        trimmed.startsWith("#") -> {
            val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
            val text = trimmed.drop(level).trim()
            MarkdownTextResult(
                context = context,
                text = text,
                inlineState = inlineState,
                modifier = Modifier.padding(top = if (level <= 2) 10.dp else 8.dp, bottom = 4.dp),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = when (level) {
                    1 -> 22.sp
                    2 -> 19.sp
                    3 -> 17.sp
                    else -> 15.sp
                },
                fontWeight = FontWeight.SemiBold
            )
        }
        trimmed.startsWith(">") -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier.padding(end = 8.dp)
                        .size(width = 3.dp, height = 20.dp)
                        .background(MiuixTheme.colorScheme.primary)
                )
                MarkdownTextResult(
                    context = context,
                    text = trimmed.removePrefix(">").trim(),
                    inlineState = inlineState,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = bodyFontSize,
                    modifier = Modifier.weight(1f)
                )
            }
            inlineState
        }
        trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
            MarkdownBullet(context, trimmed.drop(2).trim(), inlineState, fontSize = bodyFontSize)
        }
        Regex("""^\d+[.)]\s+.*""").matches(trimmed) -> {
            val marker = trimmed.substringBefore(' ').trim()
            MarkdownBullet(context, trimmed.removePrefix(marker).trim(), inlineState, marker, bodyFontSize)
        }
        else -> {
            MarkdownTextResult(
                context = context,
                text = line,
                inlineState = inlineState,
                fontSize = bodyFontSize,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun MarkdownBullet(
    context: Context,
    text: String,
    inlineState: MarkdownInlineState,
    marker: String = "•",
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp
): MarkdownInlineState {
    var nextState = inlineState
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = marker,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = fontSize,
            modifier = Modifier.padding(end = 8.dp)
        )
        nextState = MarkdownTextResult(
            context = context,
            text = text,
            inlineState = inlineState,
            fontSize = fontSize,
            modifier = Modifier.weight(1f)
        )
    }
    return nextState
}

@Composable
fun CodeBlock(code: String, onCopy: ((String) -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant)
    ) {
        if (onCopy != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "代码",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                Image(
                    imageVector = NavIcons.Copy,
                    contentDescription = "复制代码",
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
                    modifier = Modifier.size(26.dp).clip(RoundedCornerShape(4.dp))
                        .clickable { onCopy(code) }
                        .padding(6.dp)
                )
            }
        }
        Text(
            text = code,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun Text(
    context: Context,
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.onSurface,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    fontWeight: FontWeight? = null
) {
    MarkdownTextResult(context, text, MarkdownInlineState(), modifier, color, fontSize, fontWeight)
}

@Composable
private fun MarkdownTextResult(
    context: Context,
    text: String,
    inlineState: MarkdownInlineState,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.onSurface,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    fontWeight: FontWeight? = null
): MarkdownInlineState {
    val primary = MiuixTheme.colorScheme.primary
    val result = remember(text, primary, inlineState.bold) {
        buildMarkdownAnnotatedString(text, primary, inlineState)
    }
    ClickableText(
        text = result.text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = fontSize, fontWeight = fontWeight),
        onClick = { offset ->
            result.text.getStringAnnotations(MARKDOWN_LINK_TAG, offset, offset)
                .firstOrNull()
                ?.let { annotation -> openMarkdownLink(context, annotation.item) }
        }
    )
    return result.state
}

private data class MarkdownInlineState(val bold: Boolean = false)

private data class MarkdownInlineResult(
    val text: AnnotatedString,
    val state: MarkdownInlineState
)

private fun buildMarkdownAnnotatedString(
    text: String,
    accent: Color,
    initialState: MarkdownInlineState
): MarkdownInlineResult {
    var state = initialState
    val annotated = buildAnnotatedString {
        state = appendInlineMarkdown(text, accent, state)
    }
    return MarkdownInlineResult(annotated, state)
}

private fun AnnotatedString.Builder.appendInlineMarkdown(
    text: String,
    accent: Color,
    initialState: MarkdownInlineState
): MarkdownInlineState {
    var state = initialState
    var segmentStart = 0
    MARKDOWN_LINK_REGEX.findAll(text).forEach { match ->
        if (match.range.first > segmentStart) {
            state = appendInlineMarkdownSegment(
                text.substring(segmentStart, match.range.first),
                accent,
                state
            )
        }
        val label = match.groupValues[1]
        val url = match.groupValues[2].trim()
        if (label.isNotBlank() && url.isNotBlank()) {
            pushStringAnnotation(MARKDOWN_LINK_TAG, url)
            appendStyledInline(label, accent, state, link = true)
            pop()
        } else {
            append(match.value)
        }
        segmentStart = match.range.last + 1
    }
    if (segmentStart < text.length) {
        state = appendInlineMarkdownSegment(text.substring(segmentStart), accent, state)
    }
    return state
}

private fun AnnotatedString.Builder.appendInlineMarkdownSegment(
    text: String,
    accent: Color,
    initialState: MarkdownInlineState
): MarkdownInlineState {
    var state = initialState
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                state = state.copy(bold = !state.bold)
                index += 2
            }
            text[index] == '`' -> {
                val end = text.indexOf('`', index + 1)
                if (end > index) {
                    withStyle(SpanStyle(color = accent, fontFamily = FontFamily.Monospace)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            else -> {
                appendStyledInline(text[index].toString(), accent, state, link = false)
                index++
            }
        }
    }
    return state
}

private fun AnnotatedString.Builder.appendStyledInline(
    value: String,
    accent: Color,
    state: MarkdownInlineState,
    link: Boolean
) {
    val style = SpanStyle(
        color = if (link) accent else Color.Unspecified,
        fontWeight = when {
            link -> FontWeight.Medium
            state.bold -> FontWeight.SemiBold
            else -> null
        }
    )
    withStyle(style) {
        append(value)
    }
}

private fun openMarkdownLink(context: Context, url: String) {
    val value = url.trim()
    if (value.isBlank()) return
    runCatching {
        val normalized = if (value.contains("://")) value else "https://$value"
        val uri = Uri.parse(normalized)
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

}


private const val PRESS_RELEASE_DELAY_MS = 110L
private const val RINGTONE_SYSTEM_REQUEST_CODE = 0x48435254
private const val RINGTONE_FILE_REQUEST_CODE = 0x48435255
private const val REDPACKET_REPLY_FILE_REQUEST_CODE = 0x48435256
private const val AUTO_REPLY_FILE_REQUEST_CODE = 0x48435257
private const val CONFIG_EXPORT_REQUEST_CODE = 0x48435258
private const val CONFIG_IMPORT_REQUEST_CODE = 0x48435259
private const val SCHEDULED_TASK_FILE_REQUEST_CODE = 0x4843525A
private const val AUDIO_TRANSFORM_INPUT_REQUEST_CODE = 0x4843525B
private const val AUDIO_TRANSFORM_OUTPUT_REQUEST_CODE = 0x4843525C
private const val FAKE_LOCATION_WECHAT_PICKER_REQUEST_CODE = 0x4843525D
private const val SCRIPT_AGENT_ATTACHMENT_REQUEST_CODE = 0x4843525E
private const val PLUGIN_MARKET_EXTRA_FILE_REQUEST_CODE = 0x4843525F
private const val SCRIPT_PLUGIN_EXPORT_REQUEST_CODE = 0x48435260
private const val SCRIPT_PLUGIN_IMPORT_REQUEST_CODE = 0x48435261
private const val MARKDOWN_LINK_TAG = "md_link"
private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
private val NAVIGATION_BUTTON_MIN_INSET = 24.dp
private val NAVIGATION_BUTTON_EXTRA_GAP = 8.dp

private class SettingsBackHandlerRegistry {
    private val handlers = LinkedHashMap<Any, () -> Unit>()

    @Synchronized
    fun register(token: Any, handler: () -> Unit) {
        handlers.remove(token)
        handlers[token] = handler
    }

    @Synchronized
    fun unregister(token: Any) {
        handlers.remove(token)
    }

    fun handle(): Boolean {
        val handler = synchronized(this) { handlers.values.lastOrNull() } ?: return false
        handler()
        return true
    }

    @Synchronized
    fun clear() {
        handlers.clear()
    }
}

private val LocalSettingsBackHandlerRegistry = staticCompositionLocalOf<SettingsBackHandlerRegistry?> { null }

@Composable
private fun RegisterSettingsBackHandler(onBack: (() -> Unit)?) {
    val registry = LocalSettingsBackHandlerRegistry.current
    val currentOnBack by rememberUpdatedState(onBack)
    val token = remember { Any() }
    DisposableEffect(registry, token, onBack != null) {
        if (registry != null && onBack != null) {
            registry.register(token) { currentOnBack?.invoke() }
        }
        onDispose { registry?.unregister(token) }
    }
}

private fun Intent.preferSystemDocumentsUi(context: Context): Intent {
    val candidates = listOf(
        "com.google.android.documentsui",
        "com.android.documentsui"
    )
    for (packageName in candidates) {
        val copy = Intent(this).setPackage(packageName)
        val resolved = runCatching {
            context.packageManager.queryIntentActivities(copy, 0)
        }.getOrDefault(emptyList())
        if (resolved.isNotEmpty()) {
            setPackage(packageName)
            break
        }
    }
    return this
}

private object ScriptPluginDocumentBridge {
    private val hookedClasses = HashSet<Class<*>>()
    private var exportCallback: ((Uri) -> Unit)? = null
    private var importCallback: ((Uri) -> Unit)? = null
    private var exportOwner: WeakReference<Activity>? = null
    private var importOwner: WeakReference<Activity>? = null

    @Synchronized
    fun launchExport(activity: Activity, fileName: String, onPicked: (Uri) -> Unit) {
        if (exportCallback != null) {
            Toast.makeText(activity, "已有导出文件选择正在进行", Toast.LENGTH_SHORT).show()
            return
        }
        exportCallback = onPicked
        exportOwner = WeakReference(activity)
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity)
        try {
            activity.startActivityForResult(intent, SCRIPT_PLUGIN_EXPORT_REQUEST_CODE)
        } catch (_: Throwable) {
            exportCallback = null
            exportOwner = null
            Toast.makeText(activity, "当前系统不支持创建 ZIP 文件", Toast.LENGTH_SHORT).show()
        }
    }

    @Synchronized
    fun launchImport(activity: Activity, onPicked: (Uri) -> Unit) {
        if (importCallback != null) {
            Toast.makeText(activity, "已有导入文件选择正在进行", Toast.LENGTH_SHORT).show()
            return
        }
        importCallback = onPicked
        importOwner = WeakReference(activity)
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity)
        try {
            activity.startActivityForResult(intent, SCRIPT_PLUGIN_IMPORT_REQUEST_CODE)
        } catch (_: Throwable) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                activity.startActivityForResult(
                    Intent.createChooser(fallback, "选择插件 ZIP 文件"),
                    SCRIPT_PLUGIN_IMPORT_REQUEST_CODE
                )
            } catch (_: Throwable) {
                importCallback = null
                importOwner = null
                Toast.makeText(activity, "当前系统不支持选择 ZIP 文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Synchronized
    private fun hookActivityResult(clazz: Class<*>) {
        if (hookedClasses.contains(clazz)) return
        try {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    if (requestCode != SCRIPT_PLUGIN_EXPORT_REQUEST_CODE &&
                        requestCode != SCRIPT_PLUGIN_IMPORT_REQUEST_CODE
                    ) {
                        return
                    }
                    val owner = param.thisObject as? Activity ?: return
                    val callback = synchronized(this@ScriptPluginDocumentBridge) {
                        if (requestCode == SCRIPT_PLUGIN_EXPORT_REQUEST_CODE) {
                            val matches = exportOwner?.get() === owner
                            exportCallback.takeIf { matches }.also {
                                exportCallback = null
                                exportOwner = null
                            }
                        } else {
                            val matches = importOwner?.get() === owner
                            importCallback.takeIf { matches }.also {
                                importCallback = null
                                importOwner = null
                            }
                        }
                    } ?: return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    if (resultCode != Activity.RESULT_OK) return
                    val data = param.args.getOrNull(2) as? Intent ?: return
                    val uri = data.data ?: return
                    if (requestCode == SCRIPT_PLUGIN_IMPORT_REQUEST_CODE && uri.scheme == "content") {
                        runCatching {
                            val flags = data.flags and
                                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            if ((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                                owner.contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                        }
                    }
                    callback(uri)
                }
            })
            hookedClasses.add(clazz)
        } catch (_: Throwable) {
        }
    }
}

@Composable
private fun Modifier.responsiveTap(
    onClick: () -> Unit,
    onPressedChange: (Boolean) -> Unit = {}
): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPressedChange by rememberUpdatedState(onPressedChange)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            currentOnPressedChange(true)
            val up = waitForUpOrCancellation()
            currentOnPressedChange(false)
            if (up != null) {
                currentOnClick()
            }
        }
    }
}

@Composable

private fun rememberPressFeedbackColor(pressed: Boolean): Color {
    var feedbackVisible by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) {
            feedbackVisible = true
        } else {
            delay(PRESS_RELEASE_DELAY_MS)
            feedbackVisible = false
        }
    }
    val target = if (feedbackVisible) {
        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.075f)
    } else {
        Color.Transparent
    }
    return animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = if (pressed) 90 else 210),
        label = "PressFeedback"
    ).value
}

@Composable

private data class TemplateVariable(
    val token: String,
    val label: String
)

private sealed class DetailPage {
    object Search : DetailPage()
    data class FeatureGroup(val group: FeatureGroupEntry) : DetailPage()
    data class Feature(
        val provider: FeatureSettingsProvider,
        val sourceGroup: FeatureGroupEntry? = null,
        val returnToSearch: Boolean = false
    ) : DetailPage()
    data class ScriptPluginAgent(val parentFeature: Feature? = null) : DetailPage()
    data class ScriptPluginMarket(val parentFeature: Feature? = null) : DetailPage()
    data class ScriptPluginManager(val parentFeature: Feature? = null) : DetailPage()
}

@Composable
internal fun PageScaffold(
    title: String,
    largeTitle: String,
    scrollBehavior: ScrollBehavior,
    onBack: (() -> Unit)? = null,
    topBarActions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable ((Backdrop) -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    RegisterSettingsBackHandler(onBack)
    val graphicsLayer = rememberGraphicsLayer()
    val backdrop = rememberLayerBackdrop(graphicsLayer)
    val navigationInset = navigationButtonBottomInset()
    val navigationGap = if (navigationInset > 0.dp) NAVIGATION_BUTTON_EXTRA_GAP else 0.dp
    val bottomAvoidance = navigationInset + navigationGap
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            top.yukonga.miuix.kmp.basic.Scaffold(
                topBar = {
                    TopAppBar(
                        title = title,
                        largeTitle = largeTitle,
                        scrollBehavior = scrollBehavior,
                        navigationIcon = {
                            onBack?.let { back ->
                                Box(
                                    modifier = Modifier.size(40.dp).responsiveTap(onClick = back),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        imageVector = NavIcons.Back,
                                        contentDescription = "返回",
                                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        },
                        actions = topBarActions,
                        defaultWindowInsetsPadding = true
                    )
                },
                bottomBar = {},
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                content = { padding ->
                    content(
                        PaddingValues(
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding() + bottomAvoidance
                        )
                    )
                }
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomAvoidance)
                .align(Alignment.BottomCenter)
        ) {
            bottomBar?.invoke(backdrop)
        }
    }
}

@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        cornerRadius = 18.dp
    ) {
        content()
    }
}

@Composable
internal fun SwitchRow(
    sp: SharedPreferences,
    key: String,
    title: String,
    summary: String,
    defaultValue: Boolean
) {
    var checked by remember { mutableStateOf(sp.getBoolean(key, defaultValue)) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable {
            checked = !checked
            sp.edit().putBoolean(key, checked).apply()
        }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {


@Composable
internal fun ActionRow(title: String, summary: String, onClick: () -> Unit) {
    SelectRow(title = title, summary = summary, onClick = onClick)
}

@Composable
private fun SelectRow(title: String, summary: String, onClick: () -> Unit) {

@Composable
private fun SelectionMark(selected: Boolean, multiSelect: Boolean) {
    Box(
        modifier = Modifier.size(30.dp),
        contentAlignment = Alignment.Center
    ) {
        Checkbox(
            modifier = Modifier.size(22.dp),
            state = if (selected) ToggleableState.On else ToggleableState.Off,
            onClick = null
        )
    }
}

@Composable
internal fun BottomActionBar(
    primaryText: String,
    onPrimaryClick: () -> Unit,
    secondaryText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    middleText: String? = null,
    onMiddleClick: (() -> Unit)? = null
) {
    val backAction = when {
        secondaryText == "取消" && onSecondaryClick != null -> onSecondaryClick
        middleText == "取消" && onMiddleClick != null -> onMiddleClick
        primaryText == "取消" -> onPrimaryClick
        secondaryText == "返回" && onSecondaryClick != null -> onSecondaryClick
        middleText == "返回" && onMiddleClick != null -> onMiddleClick
        primaryText == "返回" -> onPrimaryClick
        secondaryText == "关闭" && onSecondaryClick != null -> onSecondaryClick
        middleText == "关闭" && onMiddleClick != null -> onMiddleClick
        primaryText == "关闭" -> onPrimaryClick
        else -> null
    }
    RegisterSettingsBackHandler(backAction)
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MiuixTheme.colorScheme.background.copy(alpha = 0.92f))
            .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (secondaryText != null && onSecondaryClick != null) {
            BottomBarButton(
                text = secondaryText,
                modifier = Modifier.weight(1f),
                filled = false,
                onClick = onSecondaryClick
            )
        }
        if (middleText != null && onMiddleClick != null) {
            BottomBarButton(
                text = middleText,
                modifier = Modifier.weight(1f),
                filled = false,
                onClick = onMiddleClick
            )
        }
        BottomBarButton(
            text = primaryText,
            modifier = Modifier.weight(1f),
            filled = true,
            onClick = onPrimaryClick
        )
    }
}



@Composable
private fun EmptyText(text: String) {
    Text(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center
    )
}

@Composable
internal fun InsetDivider(start: Dp = 16.dp) {
    Box(
        modifier = Modifier
            .padding(start = start)
            .fillMaxWidth()

/** WCX 入口：脚本 Tab 内容（Hchat Miuix 版）。 */
@Composable
fun ScriptPluginMiuixTabContent(
    context: Context,
    onOpenManager: () -> Unit = {},
    onOpenMarket: () -> Unit = {},
    onOpenAgent: () -> Unit = {},
    onOpenReadme: (ScriptPluginRuntime.ScriptPlugin) -> Unit = {},
) {
    ScriptPluginSettingsMiuixContent.ScriptPluginSettingsContent(
        context = context,
        onOpenReadme = onOpenReadme,
        onOpenMarket = onOpenMarket,
        onOpenAgent = onOpenAgent,
        onOpenManager = onOpenManager,
    )
}

/** WCX 入口：脚本管理页（Tab 内导航）。 */
@Composable
fun ScriptPluginManagerTabPage(context: Context, onBack: () -> Unit) {
    ScriptPluginSettingsMiuixContent.ScriptPluginManagerPage(context = context, onBack = onBack)
}

/** WCX 入口：脚本 README 弹窗。 */
@Composable
fun ScriptPluginReadmeTabDialog(
    context: Context,
    plugin: ScriptPluginRuntime.ScriptPlugin,
    onClose: () -> Unit,
) {
    ScriptPluginReadmeDialog(context = context, plugin = plugin, onClose = onClose)
}
