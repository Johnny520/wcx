package com.Johnny.wcx.ui.content

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.dexkit.resolution.resolveAllDex
import com.Johnny.wcx.features.core.BaseFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.copyToClipboard
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.reflection.DexKit
import com.Johnny.wcx.utils.restartHost
import com.Johnny.wcx.utils.unreachable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import java.io.PrintWriter
import java.io.StringWriter
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableLongStateOf
import com.Johnny.wcx.BuildConfig

private sealed class ScanProgress {
    data class Start(val displayName: String) : ScanProgress()
    data class Complete(val displayName: String) : ScanProgress()
    data class Failed(val displayName: String, val error: Exception) : ScanProgress()
}

private sealed class ScanResult {
    data class Success(val displayName: String) : ScanResult()
    data class Failed(val displayName: String, val error: Exception) : ScanResult()
}

private sealed class DialogPhase {
    object Idle : DialogPhase()
    object Scanning : DialogPhase()
    data class Done(val failed: List<ScanResult.Failed>) : DialogPhase()
    data class Error(val message: String) : DialogPhase()
}

private val TAG = "DexResolver"

@Composable
fun DexResolver(
    context: Context,
    outdatedItems: List<IResolveDex>,
    scope: CoroutineScope,
    dismiss: () -> Unit
) {
    var phase by remember { mutableStateOf<DialogPhase>(DialogPhase.Idle) }
    var currentTask by remember { mutableStateOf("正在适配...") }
    var completed by remember { mutableIntStateOf(0) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var showDetails by remember { mutableStateOf(false) }
    val scanResults = remember { mutableStateMapOf<String, ScanResult>() }

    fun updateProgress(progress: ScanProgress) {
        when (progress) {
            is ScanProgress.Complete -> {
                scanResults[progress.displayName] = ScanResult.Success(progress.displayName)
                completed = scanResults.size
                currentTask = "已完成: ${progress.displayName}"
            }

            is ScanProgress.Failed -> {
                scanResults[progress.displayName] = ScanResult.Failed(progress.displayName, progress.error)
                completed = scanResults.size
                currentTask = "失败: ${progress.displayName}"
            }

            else -> {}
        }
    }

    suspend fun scanItem(
        item: IResolveDex,
        dexKit: DexKitBridge,
        progressChannel: Channel<ScanProgress>
    ): ScanResult {
        val displayName = if (item is BaseFeature) item.displayName else unreachable()
        return try {
            progressChannel.send(ScanProgress.Start(displayName))

            item.resolveAllDex(dexKit)

            DexCacheManager.saveItemCache(item)
            progressChannel.send(ScanProgress.Complete(displayName))
            ScanResult.Success(displayName)
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to scan: $displayName", e)
            progressChannel.send(ScanProgress.Failed(displayName, e))
            ScanResult.Failed(displayName, e)
        }
    }

    fun startScanning() {
        phase = DialogPhase.Scanning
        completed = 0
        elapsedMs = 0L
        scanResults.clear()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        scope.launch {
            try {
                val progressChannel = Channel<ScanProgress>(Channel.UNLIMITED)

                // progress consumer on Main
                launch(Dispatchers.Main) {
                    for (p in progressChannel) updateProgress(p)
                }

                // parallel scan — same flow/buffer/async structure
                val results = outdatedItems.asFlow()
                    .map { item ->
                        async(Dispatchers.IO) {
                            scanItem(
                                item,
                                DexKit,
                                progressChannel
                            )
                        }
                    }
                    .buffer(8)
                    .map { it.await() }
                    .toList()

                progressChannel.close()

                val failed = results.filterIsInstance<ScanResult.Failed>()
                elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt
                phase = DialogPhase.Done(failed)
            } catch (e: Exception) {
                WeLogger.e(TAG, "scanning failed", e)
                phase = DialogPhase.Error("扫描过程中发生未知错误: ${e.message}")
            }
        }
    }

    val total = outdatedItems.size
    val okCount = scanResults.values.count { it is ScanResult.Success }
    val failCount = scanResults.values.count { it is ScanResult.Failed }
    val pendingCount = (total - okCount - failCount).coerceAtLeast(0)
    val hostInfo = remember { hostVersionInfo(context) }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---- 顶栏：标题 + 关闭 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DEX 适配",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = dismiss) { Text("✕") }
            }

            // ---- 状态摘要 ----
            val (statusTitle, statusLine) = when (val p = phase) {
                is DialogPhase.Idle -> "等待适配" to
                        "检测到 $total 个功能需要更新 DEX 缓存；直接关闭对话框相关功能不会被加载。"
                is DialogPhase.Scanning -> "正在适配…" to (currentTask)
                is DialogPhase.Done -> if (p.failed.isEmpty())
                    "适配检查完成" to "已检查 $total / $total 项，本轮接口检查全部通过。"
                else
                    "适配完成（部分失败）" to "已检查 $total / $total 项，其中 ${p.failed.size} 项失败（不影响其他功能使用）。"
                is DialogPhase.Error -> "适配失败" to p.message
            }
            Text(text = statusTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(text = statusLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // ---- 版本信息 ----
            val moduleVer = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            Text(
                text = "模块版本：$moduleVer\n当前微信：${hostInfo.first}\n内部版本：${hostInfo.second}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- 蓝色分隔线 ----
            HorizontalDivider(color = MaterialTheme.colorScheme.primary, thickness = 2.dp)

            // ---- 四项统计 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCell("耗时", if (elapsedMs > 0) "%.1fs".format(elapsedMs / 1000f) else "-", Modifier.weight(1f))
                StatCell("通过", "$okCount", Modifier.weight(1f))
                StatCell("异常", "$failCount", Modifier.weight(1f))
                StatCell("待验证", "$pendingCount", Modifier.weight(1f))
            }
            Text(
                text = "检查通过表示接口可用，不等于所有使用场景已验证。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- 扫描进度 ----
            AnimatedVisibility(visible = phase is DialogPhase.Scanning) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearWavyProgressIndicator(
                        progress = { if (total == 0) 0f else completed.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                        amplitude = { progress -> if (progress == 0f || progress == 1f) 0f else 1f }
                    )
                    Text(text = "总进度: $completed/$total", style = MaterialTheme.typography.labelSmall)
                }
            }

            // ---- 检查明细（可展开） ----
            if (total > 0) {
                TextButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (showDetails) "收起明细 ∧" else "检查明细 ∨") }
            }
            AnimatedVisibility(visible = showDetails) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    scanResults.values.forEach { r ->
                        val ok = r is ScanResult.Success
                        val name = when (r) {
                            is ScanResult.Success -> r.displayName
                            is ScanResult.Failed -> r.displayName
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(name, style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = if (ok) "通过" else "异常",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (ok) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    val donePhase = phase as? DialogPhase.Done
                    donePhase?.failed?.takeIf { it.isNotEmpty() }?.let { failed ->
                        ErrorDetailsSection(
                            failedResults = failed,
                            onCopy = {
                                copyToClipboard(context, buildErrorReport(failed))
                                showToast(context, "已复制")
                            }
                        )
                    }
                }
            }

            // ---- 按钮组 ----
            if (phase is DialogPhase.Idle) {
                Button(onClick = ::startScanning, modifier = Modifier.fillMaxWidth()) { Text("开始适配") }
            }
            if (phase is DialogPhase.Done || phase is DialogPhase.Error) {
                Button(
                    onClick = {
                        dismiss()
                        restartHost()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("重启微信") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val failed = scanResults.values.filterIsInstance<ScanResult.Failed>()
                        if (failed.isEmpty()) {
                            showToast(context, "暂无失败明细，已复制适配摘要")
                            copyToClipboard(context, buildErrorReport(emptyList()))
                        } else {
                            copyToClipboard(context, buildErrorReport(failed))
                            showToast(context, "已复制详细报告")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("详细报告") }
                if (phase !is DialogPhase.Scanning) {
                    OutlinedButton(onClick = { startScanning() }, modifier = Modifier.weight(1f)) {
                        Text("重新检查")
                    }
                }
            }
        }
    }
}

/** 统计小卡片：上方标签 + 下方数值。 */
@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** 宿主（微信）版本信息：versionName to longVersionCode。 */
private fun hostVersionInfo(context: Context): Pair<String, Long> = try {
    val info = context.packageManager.getPackageInfo("com.tencent.mm", 0)
    val name = info.versionName ?: "未知"
    val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION") info.versionCode.toLong()
    }
    name to code
} catch (e: Throwable) {
    WeLogger.e(TAG, "读取宿主版本失败", e)
    "未知" to 0L
}

@Composable
private fun ErrorDetailsSection(
    failedResults: List<ScanResult.Failed>,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val errorText = buildString {
                failedResults.forEachIndexed { i, r ->
                    append("${i + 1}. ${r.displayName}\n")
                    append("   错误: ${r.error.message}\n\n")
                }
            }
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState())
            )
            TextButton(onClick = onCopy) { Text("复制错误信息") }
        }
    }
}

private fun buildErrorReport(failedResults: List<ScanResult.Failed>) = buildString {
    append("=== WCX Dex 扫描报告 ===\n\n")
    if (failedResults.isEmpty()) {
        append("无失败项。\n")
        return@buildString
    }
    failedResults.forEachIndexed { i, r ->
        append("${i + 1}. ${r.displayName}\n")
        append("   错误信息: ${r.error.message}\n")
        append("   堆栈跟踪:\n")
        val sw = StringWriter()
        r.error.printStackTrace(PrintWriter(sw))
        append(sw.toString())
        append("\n\n")
    }
}
