package com.Johnny.wcx.activity.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.core.content.FileProvider
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Share

import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.content.liquid.vibrancy
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.crash.CrashLogsManager
import com.Johnny.wcx.utils.formatBytesSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.name

private const val LOGS_TAG = "LogsPager"
private const val PREVIEW_LINES = 40

/** 从日志文件首行提取的元数据 */
private data class LogFileMeta(
    val tag: String,      // 类名/接口名
    val time: String,     // 时间（HH:mm:ss.SSS）
)

/** 从日志文件第一行提取类名和时间 */
private fun parseLogMeta(file: Path): LogFileMeta {
    return runCatching {
        val f = file.toFile()
        if (f.length() == 0L) return LogFileMeta(file.name, "")
        BufferedReader(InputStreamReader(FileInputStream(f), StandardCharsets.UTF_8)).use { reader ->
            val firstLine = reader.readLine() ?: return LogFileMeta(file.name, "")
            // 格式: "yyyy-MM-dd HH:mm:ss.SSS LEVEL/TAG tag: message"
            // 提取时间部分（索引11-23: "HH:mm:ss.SSS"）
            val time = if (firstLine.length >= 23) firstLine.substring(11, 23) else ""
            // 提取 tag: 在 "LEVEL/TAG " 之后，":" 之前
            val tagStart = firstLine.indexOf(' ', 24)
            if (tagStart >= 0) {
                val tagEnd = firstLine.indexOf(':', tagStart + 1)
                if (tagEnd >= 0) {
                    val tag = firstLine.substring(tagStart + 1, tagEnd).trim()
                    return LogFileMeta(tag, time)
                }
            }
            // 回退：使用文件名
            LogFileMeta(file.name, time)
        }
    }.getOrElse {
        LogFileMeta(file.name, "")
    }
}

// ---------------------------------------------------------------------------
//  Root — two-level navigation (list → detail)
// ---------------------------------------------------------------------------

@Composable
fun LogsPager() {
    // Navigation state: null = list page, Path = detail page for that file
    var detailFile by remember { mutableStateOf<Path?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    if (detailFile == null) {
        LogListPage(
            refreshKey = refreshKey,
            onViewFile = { detailFile = it },
        )
    } else {
        LogDetailPage(
            file = detailFile!!,
            onBack = {
                detailFile = null
                refreshKey++
            },
            onDeleted = {
                refreshKey++
                detailFile = null
            },
        )
    }
}

// ---------------------------------------------------------------------------
//  Page 1 — File list
// ---------------------------------------------------------------------------

@Composable
private fun LogListPage(
    refreshKey: Int,
    onViewFile: (Path) -> Unit,
) {
    var allFiles by remember { mutableStateOf<List<Path>>(emptyList()) }
    var fileMetas by remember { mutableStateOf<Map<String, LogFileMeta>>(emptyMap()) }
    var listed by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        allFiles = withContext(Dispatchers.IO) {
            val runLogs = WeLogger.allLogFiles
            val crashLogs = CrashLogsManager.allCrashLogs
            (runLogs + crashLogs)
                .sortedByDescending { runCatching { it.fileSize() }.getOrDefault(0L) }
        }
        fileMetas = withContext(Dispatchers.IO) {
            allFiles.associate { it.name to parseLogMeta(it) }
        }
        listed = true
    }

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
            )
        },
        popupHost = {},
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(barBackdrop)
                .scrollEndHaptic()
                .overScrollVertical(),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            overscrollEffect = null,
        ) {
            if (allFiles.isEmpty()) {
                if (listed) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 64.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "暂无日志",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(allFiles, key = { _, f -> f.name }) { index, file ->
                    val meta = fileMetas[file.name]
                    LogFileItem(
                        meta = meta,
                        onView = { onViewFile(file) },
                        modifier = Modifier.padding(horizontal = 12.dp).then(
                            if (index == 0) Modifier.padding(top = 12.dp) else Modifier
                        ),
                    )
                }
            }

            item(key = "bottom-inset") {
                Spacer(Modifier.height(CONTENT_BOTTOM_INSET))
            }
        }
    }
}

@Composable
private fun LogFileItem(
    meta: LogFileMeta?,
    onView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onView,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = meta?.tag ?: "Unknown",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (!meta?.time.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = meta!!.time,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Page 2 — File detail
// ---------------------------------------------------------------------------

@Composable
private fun LogDetailPage(
    file: Path,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalComponentActivity.current
    val scope = rememberCoroutineScope()

    var previewLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var isEmpty by remember { mutableStateOf(false) }
    var fileSize by remember { mutableStateOf(0L) }
    var fileMeta by remember { mutableStateOf<LogFileMeta?>(null) }
    var loading by remember { mutableStateOf(true) }

    // ---- 确认对话框状态 ----
    var showCopyDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var copyLoading by remember { mutableStateOf(false) }
    var deleteLoading by remember { mutableStateOf(false) }

    // 流式读取前 PREVIEW_LINES 行
    LaunchedEffect(file) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            readLogHead(file, PREVIEW_LINES)
        }
        previewLines = result.lines
        isEmpty = result.isEmpty
        fileMeta = withContext(Dispatchers.IO) { parseLogMeta(file) }
        fileSize = withContext(Dispatchers.IO) {
            runCatching { file.fileSize() }.getOrDefault(0L)
        }
        loading = false
    }

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
                title = fileMeta?.tag ?: file.name,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Arrow_back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
        popupHost = {},
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
        ) {
            // 时间 + 文件大小
            if (!fileMeta?.time.isNullOrEmpty()) {
                Text(
                    text = fileMeta!!.time,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Text(
                text = if (fileSize > 0) "文件大小：${formatBytesSize(fileSize)}" else "",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )

            // 三个操作按钮（放在内容上方）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    text = "复制全部日志",
                    icon = MaterialSymbols.Outlined.Content_copy,
                    onClick = { showCopyDialog = true },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    text = "分享日志",
                    icon = MaterialSymbols.Outlined.Share,
                    onClick = { showShareDialog = true },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    text = "删除日志",
                    icon = MaterialSymbols.Outlined.Delete,
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))

            if (loading) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("加载中...", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            } else if (previewLines.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isEmpty) "日志为空" else "读取日志失败",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                // 内容区域 + 毛玻璃渐变遮罩
                Box(modifier = Modifier.weight(1f)) {
                    // 不可滚动内容
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Column {
                                previewLines.forEachIndexed { index, line ->
                                    Text(
                                        text = line,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }

                    // 渐变遮罩：200dp 从透明到半透明白，覆盖底部形成毛玻璃效果
                    // 只在预读行数 > 40 时显示（说明文件还有更多内容）
                    if (previewLines.size > PREVIEW_LINES) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0x00FFFFFF),
                                            Color(0xE6FFFFFF),
                                        ),
                                    ),
                                ),
                        )
                    }
                }
            }
        }
    }

    // ---- 复制确认对话框 ----
    if (showCopyDialog) {
        AlertDialogContent(
            title = { Text("复制全部日志") },
            text = { Text("确定要复制全部日志内容到剪贴板吗？") },
            dismissButton = {
                TextButton(onClick = { showCopyDialog = false }) { Text("取消") }
            },
            confirmButton = {
                Button(
                    enabled = !copyLoading,
                    onClick = {
                        copyLoading = true
                        scope.launch {
                            val content = withContext(Dispatchers.IO) {
                                runCatching {
                                    file.toFile().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                                }.getOrNull()
                            }
                            if (content != null) {
                                withContext(Dispatchers.Main) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("log", content))
                                    showToast("已复制到剪贴板")
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    showToast("复制失败")
                                }
                            }
                            copyLoading = false
                            showCopyDialog = false
                        }
                    },
                ) {
                    Text(if (copyLoading) "复制中..." else "复制")
                }
            },
        )
    }

    // ---- 分享确认对话框 ----
    if (showShareDialog) {
        AlertDialogContent(
            title = { Text("分享日志") },
            text = { Text("确定要分享此日志文件吗？") },
            dismissButton = {
                TextButton(onClick = { showShareDialog = false }) { Text("取消") }
            },
            confirmButton = {
                Button(onClick = {
                    showShareDialog = false
                    shareLogFile(context, file)
                }) { Text("分享") }
            },
        )
    }

    // ---- 删除确认对话框 ----
    if (showDeleteDialog) {
        AlertDialogContent(
            title = { Text("删除日志") },
            text = { Text("确定删除此日志文件吗？") },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
            confirmButton = {
                Button(
                    enabled = !deleteLoading,
                    onClick = {
                        deleteLoading = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { file.toFile().delete() }
                            }
                            deleteLoading = false
                            showDeleteDialog = false
                            onDeleted()
                            onBack()
                        }
                    },
                ) {
                    Text(if (deleteLoading) "删除中..." else "删除")
                }
            },
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------------------
//  File operations
// ---------------------------------------------------------------------------

/** 流式读取文件前 [maxLines] 行，返回行列表 + 是否为空文件 */
private data class HeadRead(
    val lines: List<String>,
    val isEmpty: Boolean,
)

private fun readLogHead(file: Path, maxLines: Int): HeadRead {
    return runCatching {
        val f = file.toFile()
        if (f.length() == 0L) return HeadRead(emptyList(), true)
        val lines = ArrayList<String>(maxLines)
        BufferedReader(InputStreamReader(FileInputStream(f), StandardCharsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null && lines.size < maxLines) {
                lines.add(line)
                line = reader.readLine()
            }
        }
        HeadRead(lines, false)
    }.getOrElse {
        WeLogger.e(LOGS_TAG, "failed to read log head", it)
        HeadRead(listOf("读取日志失败: ${it.message}"), false)
    }
}

/** 通过 FileProvider 分享日志文件 */
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
            putExtra(
                Intent.EXTRA_TEXT,
                runCatching { readLogHead(file, 200).lines.joinToString("\n") }.getOrDefault(""),
            )
        }
    }
    val chooser = Intent.createChooser(sendIntent, "分享日志")
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(chooser) }
        .onFailure { WeLogger.e(LOGS_TAG, "failed to launch share chooser", it) }
}