package com.Johnny.wcx.activity.settings

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete_sweep
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.More_vert
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Save
import com.composables.icons.materialsymbols.outlined.Share
import com.composables.icons.materialsymbols.outlined.Vertical_align_bottom
import com.composables.icons.materialsymbols.outlined.Vertical_align_top
import com.Johnny.wcx.activity.TransparentActivity
import com.Johnny.wcx.ui.content.liquid.vibrancy
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToastSuspend
import com.Johnny.wcx.utils.crash.CrashLogsManager
import com.Johnny.wcx.utils.formatBytesSize
import com.Johnny.wcx.utils.formatEpoch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name
import kotlin.io.path.readText
import androidx.compose.animation.core.tween as animTween

private const val LOGS_TAG = "SettingsActivity"

// ---- 分页读取常量 ----
private const val PAGE_SIZE = 500
private const val MAX_CACHED_LINES = 2000
private const val LARGE_FILE_THRESHOLD = 20L * 1024 * 1024
private const val MEDIUM_FILE_THRESHOLD = 5L * 1024 * 1024

/** Which log kind a page is showing. */
private enum class LogKind { RUN, CRASH }

// ---------------------------------------------------------------------------
//  Parsed models
// ---------------------------------------------------------------------------

/** One run-log entry: a header line plus any continuation (stack-trace) lines folded into [message]. */
private data class RunLogEntry(
    val time: String?,
    val level: Char?,
    val tag: String?,
    val message: String,
)

/** One crash-report section: a "==== Title ====" block and the body lines beneath it. */
private data class CrashSection(
    val title: String,
    val body: String,
)

// WeLogger writes each entry as: "$ts $level/$TAG $tag: $msg"
//   ts    = yyyy-MM-dd HH:mm:ss.SSS
//   level = one of V D I W E A
//   $TAG  = BuildConfig.TAG (the module tag), $tag = caller tag
// e.g. "2026-07-05 14:30:22.123 E/WeKit AggregateChats: something failed"
// Groups: 1=date 2=time(+ms) 3=level 4=moduleTag 5=callerTag 6=message
private val RUN_LOG_REGEX = Regex(
    """^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}\.\d{3}) ([VDIWEAF])/(\S+)\s+([^:]*): (.*)$""",
)

/**
 * Parses raw run-log lines into [RunLogEntry] cards. A line matching [RUN_LOG_REGEX] starts a new
 * card; any other line (stack-trace continuation, multi-line message) folds into the previous card
 * so multi-line entries stay together. Leading orphan lines become metadata-less cards.
 */
private fun parseRunLog(text: String): List<RunLogEntry> {
    val out = ArrayList<RunLogEntry>()
    for (line in text.lineSequence()) {
        if (line.isEmpty() && out.isEmpty()) continue
        val m = RUN_LOG_REGEX.matchEntire(line)
        when {
            m != null -> {
                val (_, time, level, _, tag, msg) = m.destructured
                out.add(
                    RunLogEntry(
                        time = time,
                        level = level.firstOrNull(),
                        tag = tag.trim().ifEmpty { null },
                        message = msg,
                    ),
                )
            }

            out.isNotEmpty() -> {
                val prev = out.removeAt(out.size - 1)
                out.add(prev.copy(message = if (prev.message.isEmpty()) line else prev.message + "\n" + line))
            }

            else -> out.add(RunLogEntry(time = null, level = null, tag = null, message = line))
        }
    }
    return out
}

/**
 * Splits a crash report into [CrashSection] cards. The report format (see CrashInfoCollector) is a
 * sequence of `"===="` fenced blocks: a fence line, a title line, a fence line, then the body up to
 * the next fence. Any preamble before the first section becomes its own untitled card.
 */
private fun parseCrashLog(text: String): List<CrashSection> {
    val lines = text.lines()
    val fence = "========================================"
    val out = ArrayList<CrashSection>()

    var i = 0
    val preamble = StringBuilder()
    // Collect anything before the first fenced title as a preamble card.
    while (i < lines.size && !(lines[i] == fence && i + 2 < lines.size && lines[i + 2] == fence)) {
        preamble.appendLine(lines[i]); i++
    }
    val pre = preamble.toString().trim()
    if (pre.isNotEmpty()) out.add(CrashSection(title = "", body = pre))

    while (i < lines.size) {
        // Expect: fence / title / fence / body...
        if (lines[i] == fence && i + 2 < lines.size && lines[i + 2] == fence) {
            val title = lines[i + 1].trim()
            i += 3
            val body = StringBuilder()
            while (i < lines.size && !(lines[i] == fence && i + 2 < lines.size && lines[i + 2] == fence)) {
                body.appendLine(lines[i]); i++
            }
            out.add(CrashSection(title = title, body = body.toString().trim()))
        } else {
            i++
        }
    }
    return out
}

// ---------------------------------------------------------------------------
//  File operations: share (FileProvider) + save (SAF)
// ---------------------------------------------------------------------------

/**
 * Shares a log file as a text/plain attachment. Uses WeChat's built-in FileProvider authority
 * (`<host>.external.fileprovider`, whose paths cover external + root storage) since this activity
 * runs inside the host process. Falls back to sharing the file's text inline if the provider throws.
 */
private fun shareLogFile(context: Context, file: Path) {
    val f = file.toFile()
    val authority = "${HostInfo.packageName}.external.fileprovider"
    val sendIntent = runCatching {
        val uri = FileProvider.getUriForFile(context, authority, f)
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrElse {
        WeLogger.w(LOGS_TAG, "FileProvider share failed, falling back to inline text", it)
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            // 回退时仅读取末尾内容，避免大文件 OOM
            putExtra(Intent.EXTRA_TEXT, runCatching {
                readLogTail(file, 200).lines.joinToString("\n")
            }.getOrDefault(""))
        }
    }
    val chooser = Intent.createChooser(sendIntent, "分享日志")
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(chooser) }
        .onFailure { WeLogger.e(LOGS_TAG, "failed to launch share chooser", it) }
}

/**
 * Opens the system document creator so the user can save a copy of [file] wherever they choose,
 * mirroring the config-export flow in SettingsActivity.
 */
private fun saveLogFile(context: Context, file: Path) {
    TransparentActivity.launch(context) {
        val launcher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            if (uri == null) {
                finish(); return@registerForActivityResult
            }
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri, "w")!!.use { out ->
                        file.toFile().inputStream().use { it.copyTo(out) }
                    }
                }.onFailure {
                    WeLogger.e(LOGS_TAG, "failed to save log", it)
                    showToastSuspend("保存失败!")
                }.onSuccess { showToastSuspend("保存成功") }
                withContext(Dispatchers.Main) { finish() }
            }
        }
        launcher.launch(file.name)
    }
}

// Bottom padding so scrollable content clears the floating bar (mirrors SettingsActivity's inset).
private val LOGS_BOTTOM_INSET = 88.dp

private val LOG_TABS = listOf("运行日志" to LogKind.RUN, "崩溃日志" to LogKind.CRASH)

// ---------------------------------------------------------------------------
//  Page 2 — Logs
// ---------------------------------------------------------------------------

@Composable
fun LogsPager() {
    val context = LocalComponentActivity.current
    val scope = rememberCoroutineScope()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val kind = LOG_TABS[selectedTab].second

    // One LazyListState per tab, retained across refreshes so scroll position survives a reload.
    val runListState = rememberLazyListState()
    val crashListState = rememberLazyListState()
    val listState = if (kind == LogKind.RUN) runListState else crashListState

    // Bumping this key forces the visible tab to re-list its files and re-read the selection.
    var refreshKey by remember { mutableIntStateOf(0) }
    // The file currently selected in the visible tab, hoisted so the toolbar can share/save it.
    var currentFile by remember { mutableStateOf<Path?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    val scrollBehavior = MiuixScrollBehavior()
    val barBackdrop = rememberLayerBackdrop()
    val barTint = MiuixTheme.colorScheme.surface.copy(alpha = 0.67f)

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.drawBackdrop(
                    backdrop = barBackdrop,
                    shape = { RectangleShape },
                    effects = {
                        vibrancy()
                        blur(24.dp.toPx(), 24.dp.toPx())
                    },
                    onDrawSurface = { drawRect(barTint) },
                ),
                color = Color.Transparent,
                title = "日志",
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = {
                        currentFile?.let { shareLogFile(context, it) }
                            ?: scope.launch { showToastSuspend("暂无可分享的日志") }
                    }) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Share,
                            contentDescription = "分享",
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                    IconButton(onClick = {
                        currentFile?.let { saveLogFile(context, it) }
                            ?: scope.launch { showToastSuspend("暂无可保存的日志") }
                    }) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Save,
                            contentDescription = "保存",
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                    // Native miuix overflow menu (ListPopup), matching the Settings-page dropdown style.
                    val menuEntry = remember(listState) {
                        DropdownEntry(
                            items = listOf(
                                DropdownItem(
                                    text = "刷新",
                                    icon = { m -> Icon(MaterialSymbols.Outlined.Refresh, null, m) },
                                    onClick = { refreshKey++ },
                                ),
                                DropdownItem(
                                    text = "转到顶部",
                                    icon = { m -> Icon(MaterialSymbols.Outlined.Vertical_align_top, null, m) },
                                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                                ),
                                DropdownItem(
                                    text = "转到底部",
                                    icon = { m -> Icon(MaterialSymbols.Outlined.Vertical_align_bottom, null, m) },
                                    onClick = {
                                        scope.launch {
                                            val end = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                            listState.animateScrollToItem(end)
                                        }
                                    },
                                ),
                                DropdownItem(
                                    text = "清空",
                                    icon = { m -> Icon(MaterialSymbols.Outlined.Delete_sweep, null, m) },
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                when (kind) {
                                                    LogKind.RUN -> WeLogger.allLogFiles
                                                        .forEach { runCatching { it.toFile().delete() } }

                                                    LogKind.CRASH -> CrashLogsManager.deleteAllCrashLogs()
                                                }
                                            }
                                            refreshKey++
                                        }
                                    },
                                ),
                            ),
                        )
                    }
                    WindowIconDropdownMenu(entry = menuEntry) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.More_vert,
                            contentDescription = "菜单",
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
                bottomContent = {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp),
                    ) {
                        TabRow(
                            tabs = LOG_TABS.map { it.first },
                            selectedTabIndex = selectedTab,
                            onTabSelected = { selectedTab = it },
                            colors = TabRowDefaults.tabRowColors(backgroundColor = Color.Transparent),
                        )
                    }
                },
            )
        },
        popupHost = {},
    ) { innerPadding ->
        Crossfade(targetState = kind, animationSpec = tween(200), label = "logKind") { k ->
            LogTabContent(
                kind = k,
                listState = if (k == LogKind.RUN) runListState else crashListState,
                barBackdrop = barBackdrop,
                scrollBehavior = scrollBehavior,
                innerPadding = innerPadding,
                refreshKey = refreshKey,
                isRefreshing = isRefreshing,
                onRefreshingChange = { isRefreshing = it },
                onRefreshRequested = { refreshKey++ },
                onCurrentFileChange = { if (k == kind) currentFile = it },
            )
        }
    }
}

@Composable
private fun LogTabContent(
    kind: LogKind,
    listState: LazyListState,
    barBackdrop: LayerBackdrop,
    scrollBehavior: ScrollBehavior,
    innerPadding: PaddingValues,
    refreshKey: Int,
    isRefreshing: Boolean,
    onRefreshingChange: (Boolean) -> Unit,
    onRefreshRequested: () -> Unit,
    onCurrentFileChange: (Path?) -> Unit,
) {
    val context = LocalComponentActivity.current
    val scope = rememberCoroutineScope()

    // Files available for this tab, newest first.
    var files by remember(kind) { mutableStateOf<List<Path>>(emptyList()) }
    var selectedIndex by rememberSaveable(kind) { mutableIntStateOf(0) }
    // Parsed content of the selected file (type depends on kind).
    var runEntries by remember(kind) { mutableStateOf<List<RunLogEntry>>(emptyList()) }
    var crashSections by remember(kind) { mutableStateOf<List<CrashSection>>(emptyList()) }
    var loading by remember(kind) { mutableStateOf(true) }
    // Whether the file listing has completed at least once; keeps the spinner up on first open
    // until we actually know whether there are files (the selected file is null until then).
    var listed by remember(kind) { mutableStateOf(false) }

    // ---- 分页状态 ----
    var loadedLines by remember(kind) { mutableStateOf<List<String>>(emptyList()) }
    var currentOffset by remember(kind) { mutableStateOf(Long.MAX_VALUE) }
    var hasMore by remember(kind) { mutableStateOf(false) }
    var isLoadingMore by remember(kind) { mutableStateOf(false) }
    var showLargeFileDialog by remember { mutableStateOf(false) }
    var largeFilePath by remember { mutableStateOf<Path?>(null) }
    var fileNote by remember(kind) { mutableStateOf<String?>(null) }

    // (Re)list files whenever the tab is shown or a refresh is requested.
    LaunchedEffect(kind, refreshKey) {
        val result = withContext(Dispatchers.IO) {
            when (kind) {
                LogKind.RUN -> WeLogger.allLogFiles
                LogKind.CRASH -> CrashLogsManager.allCrashLogs
            }
        }
        files = result
        if (selectedIndex >= result.size) selectedIndex = 0
        listed = true
    }

    val selectedFile = files.getOrNull(selectedIndex)
    LaunchedEffect(selectedFile) { onCurrentFileChange(selectedFile) }

    // Read + parse the selected file off the main thread. refreshKey re-reads the same file;
    // `listed` gates the empty case so the spinner doesn't flash off before listing finishes.
    // 使用分页读取：默认从文件尾部加载最新 PAGE_SIZE 行，避免大文件 OOM。
    LaunchedEffect(selectedFile, refreshKey, listed) {
        loading = true
        fileNote = null
        if (selectedFile == null) {
            runEntries = emptyList(); crashSections = emptyList()
            loadedLines = emptyList()
            currentOffset = Long.MAX_VALUE
            hasMore = false
            if (listed) {
                loading = false
                onRefreshingChange(false)
            }
            return@LaunchedEffect
        }
        val fileSize = withContext(Dispatchers.IO) {
            runCatching { selectedFile.fileSize() }.getOrDefault(0L)
        }
        if (fileSize > LARGE_FILE_THRESHOLD) {
            // 大文件：弹出对话框让用户选择查看最新内容或分享完整文件
            largeFilePath = selectedFile
            showLargeFileDialog = true
            loading = false
            onRefreshingChange(false)
            return@LaunchedEffect
        }
        // 正常分页读取最后一页
        val result = withContext(Dispatchers.IO) { readLogTail(selectedFile, PAGE_SIZE) }
        loadedLines = result.lines
        currentOffset = result.nextOffset
        hasMore = result.hasMore
        if (fileSize > MEDIUM_FILE_THRESHOLD) {
            fileNote = "日志文件较大（${formatBytesSize(fileSize)}），已加载最新 ${result.lines.size} 行"
        }
        val text = result.lines.joinToString("\n")
        when (kind) {
            LogKind.RUN -> runEntries = withContext(Dispatchers.Default) { parseRunLog(text) }
            LogKind.CRASH -> crashSections = withContext(Dispatchers.Default) { parseCrashLog(text) }
        }
        loading = false
        onRefreshingChange(false)
    }

    val pullState = rememberPullToRefreshState()
    PullToRefresh(
        // Show the refresh indicator both for user pulls and while a file is being read/parsed,
        // so opening or switching to a large log surfaces the same loading affordance.
        isRefreshing = isRefreshing || loading,
        onRefresh = { onRefreshingChange(true); onRefreshRequested() },
        pullToRefreshState = pullState,
        contentPadding = innerPadding,
        topAppBarScrollBehavior = scrollBehavior,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(barBackdrop)
                .scrollEndHaptic()
                .overScrollVertical()
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "picker") {
                FileSelector(
                    files = files,
                    selectedIndex = selectedIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0)),
                    onSelected = { selectedIndex = it },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            if (files.isEmpty()) {
                // Only announce "no logs" once listing has finished, so it doesn't flash under the spinner.
                if (listed) {
                    item(key = "empty-files") { LogsEmpty(if (kind == LogKind.RUN) "暂无运行日志" else "暂无崩溃日志") }
                }
            } else {
                // 文件大小提示
                fileNote?.let { note ->
                    item(key = "file-note") {
                        Text(
                            text = note,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
                // 加载更多按钮
                if (hasMore && !loading) {
                    item(key = "load-more") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selectedFile != null && !isLoadingMore) {
                                        scope.launch {
                                            isLoadingMore = true
                                            val result = withContext(Dispatchers.IO) {
                                                readLogTail(selectedFile, PAGE_SIZE, currentOffset)
                                            }
                                            val merged = (result.lines + loadedLines).takeLast(MAX_CACHED_LINES)
                                            loadedLines = merged
                                            currentOffset = result.nextOffset
                                            hasMore = result.hasMore && merged.size < MAX_CACHED_LINES
                                            val text = merged.joinToString("\n")
                                            when (kind) {
                                                LogKind.RUN -> runEntries = withContext(Dispatchers.Default) { parseRunLog(text) }
                                                LogKind.CRASH -> crashSections = withContext(Dispatchers.Default) { parseCrashLog(text) }
                                            }
                                            isLoadingMore = false
                                        }
                                    }
                                },
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (isLoadingMore) "加载中..." else "加载更多（向前500行）",
                                    color = MiuixTheme.colorScheme.primary,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
                when (kind) {
                    LogKind.RUN -> {
                        if (runEntries.isEmpty() && !loading) {
                            item(key = "empty-run") { LogsEmpty("此日志文件为空") }
                        }
                        items(runEntries.size, key = { "run-$it" }) { i -> RunLogCard(runEntries[i]) }
                    }

                    LogKind.CRASH -> {
                        if (crashSections.isEmpty() && !loading) {
                            item(key = "empty-crash") { LogsEmpty("此日志文件为空") }
                        }
                        items(crashSections.size, key = { "crash-$it" }) { i -> CrashSectionCard(crashSections[i]) }
                    }
                }
            }

            item(key = "bottom-inset") { Spacer(Modifier.height(LOGS_BOTTOM_INSET)) }
        }
    }

    // 大文件对话框
    if (showLargeFileDialog && largeFilePath != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = {
            showLargeFileDialog = false
            largeFilePath = null
        }) {
            Card(modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("日志较大", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "日志文件较大，建议仅查看最新内容或直接分享完整文件。",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            modifier = Modifier
                                .clickable {
                                    val file = largeFilePath
                                    showLargeFileDialog = false
                                    largeFilePath = null
                                    if (file != null) {
                                        scope.launch {
                                            loading = true
                                            val result = withContext(Dispatchers.IO) {
                                                readLogTail(file, PAGE_SIZE)
                                            }
                                            loadedLines = result.lines
                                            currentOffset = result.nextOffset
                                            hasMore = result.hasMore
                                            fileNote = "日志文件较大，已加载最新 ${result.lines.size} 行"
                                            val text = result.lines.joinToString("\n")
                                            when (kind) {
                                                LogKind.RUN -> runEntries = withContext(Dispatchers.Default) { parseRunLog(text) }
                                                LogKind.CRASH -> crashSections = withContext(Dispatchers.Default) { parseCrashLog(text) }
                                            }
                                            loading = false
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text("查看最新500行", color = MiuixTheme.colorScheme.primary, fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clickable {
                                    val file = largeFilePath
                                    showLargeFileDialog = false
                                    largeFilePath = null
                                    file?.let { shareLogFile(context, it) }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text("分享完整文件", color = MiuixTheme.colorScheme.primary, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 分页读取结果：行列表 + 下次读取的字节偏移 + 是否还有更多 */
private data class PaginatedRead(
    val lines: List<String>,
    val nextOffset: Long,
    val hasMore: Boolean,
)

/**
 * 从文件尾部向前读取 [linesToRead] 行，返回按时间正序排列的行列表。
 * [fromByteOffset] 指定起始读取位置（从该位置向前读），默认从文件末尾开始。
 * 使用 RandomAccessFile 分块反向读取，避免将整个文件加载到内存。
 */
private fun readLogTail(file: Path, linesToRead: Int, fromByteOffset: Long = Long.MAX_VALUE): PaginatedRead {
    return runCatching {
        val f = file.toFile()
        val fileLength = f.length()
        if (fileLength == 0L) return@runCatching PaginatedRead(emptyList(), 0L, false)

        val startFrom = minOf(fromByteOffset, fileLength)
        val chunkSize = 8192
        val lines = ArrayDeque<String>()
        var pos = startFrom
        var hasMore = false

        RandomAccessFile(f, "r").use { raf ->
            // 处理尾部不完整行（从 fromByteOffset 开始但不是行首）
            var pending = StringBuilder()

            while (pos > 0 && lines.size < linesToRead) {
                val readLen = minOf(chunkSize, pos).toInt()
                pos -= readLen
                raf.seek(pos)
                val chunk = ByteArray(readLen)
                raf.readFully(chunk)

                // 从块末尾向前扫描换行符
                var segmentEnd = readLen
                for (i in (readLen - 1) downTo 0) {
                    if (chunk[i] == '\n'.code.toByte()) {
                        // chunk[i+1 .. segmentEnd) 是一条完整行（可能拼接 pending）
                        val segment = String(chunk, i + 1, segmentEnd - i - 1, StandardCharsets.UTF_8)
                        val fullLine = if (pending.isNotEmpty()) segment + pending else segment
                        if (fullLine.isNotEmpty()) {
                            lines.addFirst(fullLine)
                            if (lines.size >= linesToRead) {
                                hasMore = pos + i > 0
                                break
                            }
                        }
                        segmentEnd = i
                        pending = StringBuilder()
                    }
                }
                if (lines.size < linesToRead && segmentEnd > 0) {
                    // 块开头到第一个换行符之间的不完整行，拼接到 pending 前面
                    val remaining = String(chunk, 0, segmentEnd, StandardCharsets.UTF_8)
                    pending.insert(0, remaining)
                }
            }

            // 如果已读到文件开头，处理最后的 pending
            if (pos == 0 && pending.isNotEmpty() && lines.size < linesToRead) {
                lines.addFirst(pending.toString())
            }
            if (lines.size < linesToRead) hasMore = false
            else if (!hasMore) hasMore = pos > 0
        }

        PaginatedRead(lines.toList(), pos, hasMore)
    }.getOrElse {
        WeLogger.e(LOGS_TAG, "failed to read log tail", it)
        PaginatedRead(listOf("读取日志失败: ${it.message}"), 0L, false)
    }
}
// ---------------------------------------------------------------------------
//  File selector + cards + empty state
// ---------------------------------------------------------------------------

@Composable
private fun FileSelector(
    files: List<Path>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return
    val labels = remember(files) {
        files.map { "${it.name}  ·  ${formatBytesSize(runCatching { it.fileSize() }.getOrDefault(0))}" }
    }
    Card(modifier = modifier.fillMaxWidth()) {
        WindowDropdownPreference(
            title = "选择日志文件",
            summary = files.getOrNull(selectedIndex)?.let {
                formatEpoch(it.getLastModifiedTime().toMillis(), true)
            },
            items = labels,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelected,
        )
    }
}

/** Long messages (over this many lines) collapse to a preview with an expand toggle. */
private const val RUN_LOG_COLLAPSE_LINES = 5

@Composable
private fun RunLogCard(entry: RunLogEntry) {
    // Split once; if the message runs past the threshold, show a 5-line preview + expand toggle.
    val lines = remember(entry.message) { entry.message.split("\n") }
    val isLong = lines.size > RUN_LOG_COLLAPSE_LINES
    val head = remember(lines) { lines.take(RUN_LOG_COLLAPSE_LINES).joinToString("\n") }
    val rest = remember(lines) { lines.drop(RUN_LOG_COLLAPSE_LINES).joinToString("\n") }
    var expanded by remember(entry) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = animTween(250),
        label = "chevron",
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                entry.level?.let { level ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(levelColor(level))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(level.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    entry.tag?.let {
                        Text(
                            text = it,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    entry.time?.let {
                        Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                if (isLong) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Expand_more,
                            contentDescription = if (expanded) "折叠" else "展开",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(chevronRotation),
                        )
                    }
                }
            }
            if (entry.message.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Column {
                        Text(
                            text = if (isLong) head else entry.message,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        if (isLong) {
                            AnimatedVisibility(
                                visible = expanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                Text(
                                    text = rest,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashSectionCard(section: CrashSection) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            if (section.title.isNotEmpty()) {
                Text(
                    text = section.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }
            SelectionContainer {
                Text(
                    text = section.body,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun LogsEmpty(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** Log-level chip background color, matching the run-log level chars WeLogger emits. */
private fun levelColor(level: Char): Color = when (level) {
    'E', 'F', 'A' -> Color(0xFFD32F2F)
    'W' -> Color(0xFFF57C00)
    'I' -> Color(0xFF388E3C)
    'D' -> Color(0xFF1976D2)
    'V' -> Color(0xFF757575)
    else -> Color(0xFF9E9E9E)
}
