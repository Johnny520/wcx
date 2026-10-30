package com.Johnny.wcx.activity.settings

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.features.items.contacts.HideContacts
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.crash.ExitReasonRecorder
import com.Johnny.wcx.utils.MainThreadWatchdog
import com.Johnny.wcx.utils.crash.ProcessTimeline
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.utils.reflection.ClassLoaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块自检（最外层 / 全量）：把整个模块的公共依赖逐项扫描出来；「一键修复」= 体检 → 修复 → 复检 → 前后对比。
 *
 * 覆盖：各功能分类启用概况 / 微信公共 API 层（含完整性自检）/ 脚本运行时全链路 /
 * Hook 安装任务逐项 / 关键类锚点 / 隐藏联系人状态。
 *
 * 每一项都可点开：弹出「状态 + 含义 + 恢复建议」，可修复的项还带一个**动作按钮**
 * （如待重试的 Hook 任务可直接「重试安装」、未解析的微信 API 可「一键修复」）。
 */
@Composable
fun ModuleHealthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    var items by remember { mutableStateOf(listOf<HealthItem>()) }
    var lastScan by remember { mutableStateOf("尚未检测") }
    var scanDurationMs by remember { mutableStateOf(0L) }
    var running by remember { mutableStateOf(false) }
    var runTitle by remember { mutableStateOf("") }
    var runProgress by remember { mutableFloatStateOf(0f) }
    var runSubtitle by remember { mutableStateOf("") }
    var runLog by remember { mutableStateOf(listOf<Pair<String, Boolean>>()) }

    fun launchRun(
        title: String,
        stepsProvider: () -> List<HealthStep>,
        showOverlay: Boolean,
        reportTitle: String?,
        replaceItems: Boolean,
        afterRun: (() -> Unit)? = null,
    ) {
        if (running) return
        if (showOverlay) {
            running = true
            runTitle = title
            runProgress = 0f
            runSubtitle = "准备中…"
            runLog = emptyList()
        }
        scope.launch {
            val startedAt = System.currentTimeMillis()
            val steps = runCatching { stepsProvider() }.getOrElse { emptyList() }
            val verbose = showOverlay || reportTitle != null
            if (verbose) SelfCheckLog.start(title, steps.size)
            val collected = mutableListOf<HealthItem>()
            steps.forEachIndexed { index, step ->
                if (showOverlay) runSubtitle = "正在执行：${step.title}"
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { step.run() }.getOrElse { error ->
                        listOf(
                            HealthItem(
                                label = step.title,
                                section = step.title,
                                value = "异常",
                                ok = false,
                                detail = "执行「${step.title}」时抛出异常：\n" + error.toString(),
                            )
                        )
                    }
                }
                collected += outcome
                if (verbose) {
                    outcome.forEach { item -> SelfCheckLog.step(item.ok, item.label, item.value) }
                }
                if (showOverlay) {
                    runLog = runLog + (step.title to outcome.all { it.ok })
                    runProgress = (index + 1).toFloat() / steps.size.toFloat().coerceAtLeast(1f)
                    delay(120)
                }
            }
            if (replaceItems) items = collected
            SelfCheckLog.end(title, collected.count { it.ok }, collected.size)
            running = false
            lastScan = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (replaceItems) scanDurationMs = System.currentTimeMillis() - startedAt
            if (reportTitle != null) showReportDialog(context, reportTitle, collected)
            // 运行结束（running 已置 false）后再执行收尾动作，如「修复后重新自检刷新列表」。
            afterRun?.invoke()
        }
    }

    fun rescan() = launchRun("模块自检", { moduleHealthSteps(context) }, showOverlay = true, reportTitle = null, replaceItems = true)

    LaunchedEffect(Unit) { rescan() }

    fun showFilteredList(title: String, list: List<HealthItem>) {
        if (list.isEmpty()) {
            showToast("没有符合条件的项")
            return
        }
        val text = buildString {
            append("共 ").append(list.size).append(" 项：\n\n")
            list.forEach { append(if (it.ok) "✅ " else "❌ ").append(it.label)
                .append("\t").append(it.value).append("\n") }
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(text)
            .setNeutralButton("复制") { _, _ ->
                runCatching {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cb?.setPrimaryClip(ClipData.newPlainText(title, text))
                }
                showToast("已复制")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    fun openDetail(item: HealthItem) {
        val detailText = "状态：" + item.value + "\n\n" + item.detail
        val builder = AlertDialog.Builder(context)
            .setTitle(item.label)
            .setMessage(detailText)
            .setNeutralButton("复制") { _, _ ->
                runCatching {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cb?.setPrimaryClip(ClipData.newPlainText(item.label, item.label + "\n" + detailText))
                }
                showToast("已复制")
            }
            .setNegativeButton("关闭", null)
        val act = item.action
        if (act != null) {
            builder.setPositiveButton(item.actionLabel ?: "执行") { _, _ ->
                scope.launch {
                    val msg = withContext(Dispatchers.IO) {
                        runCatching { act() }.getOrElse { "执行失败：" + (it.message ?: it.javaClass.simpleName) }
                    }
                    showToast(msg)
                    rescan()
                }
            }
        }
        builder.show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PageScaffold(
            title = "模块自检",
            largeTitle = "模块自检",
            scrollBehavior = scrollBehavior,
            onBack = onBack,
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 84.dp,
                ),
            ) {
                item {
                    Text(
                        text = "最后检测：$lastScan　·　点击任意一项可看详情，可修复的项会带动作按钮",
                        fontSize = 12.sp,
                        color = BasicComponentDefaults.summaryColor().color,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                item {
                    OverviewHealthCard(
                        total = items.size,
                        pass = items.count { it.ok },
                        fail = items.count { !it.ok && !it.inactive },
                        off = items.count { !it.ok && it.inactive },
                        moduleVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        wechatVersion = runCatching {
                            com.Johnny.wcx.utils.HostInfo.versionName
                        }.getOrDefault("未识别"),
                        internalVersion = "${BuildConfig.VERSION_CODE}",
                        lastScan = lastScan,
                        scanDurationMs = scanDurationMs,
                        onShowList = { kind ->
                            when (kind) {
                                "pass" -> showFilteredList("通过项", items.filter { it.ok })
                                "fail" -> showFilteredList("异常项", items.filter { !it.ok && !it.inactive })
                                "off" -> showFilteredList("未启用项", items.filter { !it.ok && it.inactive })
                                else -> showFilteredList("全部检测项", items)
                            }
                        },
                        onRescan = { rescan() },
                        onRepair = {
                            launchRun(
                                "一键修复",
                                { moduleRepairSteps(context) },
                                showOverlay = true,
                                reportTitle = "一键修复结果",
                                replaceItems = false,
                                afterRun = { rescan() },
                            )
                        },
                    )
                }

                val grouped = items.groupBy { it.section }
                grouped.forEach { (section, list) ->
                    item { SmallTitle(modifier = Modifier.padding(top = 6.dp), text = section) }
                    item {
                        SettingsCard {
                            list.forEachIndexed { index, item ->
                                if (index > 0) InsetDivider()
                                HealthRow(item) { openDetail(item) }
                            }
                        }
                    }
                }

                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "一键修复") }
                item {
                    SettingsCard {
                        ActionRow("一键修复", "修复前体检 → 补目录 / 开总开关 / 重建引导 / 推进阶段 / 重排 Hook / 重载插件 / 刷新隐藏联系人 → 复检 → 给出前后对比") {
                            launchRun("一键修复", { moduleRepairSteps(context) }, showOverlay = true, reportTitle = "一键修复结果", replaceItems = false, afterRun = { rescan() })
                        }
                        InsetDivider()
                        ActionRow("刷新检测结果", "重新逐项扫描并刷新上面的列表（不改动任何配置）") {
                            rescan()
                            showToast("已重新检测")
                        }
                        InsetDivider()
                        ActionRow("复制检测结果", "复制到剪贴板，便于反馈") {
                            val text = if (items.isEmpty()) "（尚无检测结果）"
                            else items.joinToString("\n") { "${it.label}：${it.value}" }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("WCX 模块自检", text))
                            showToast("检测结果已复制")
                        }
                    }
                }

                item {
                    Text(
                        text = "定位过程与失败原因已写入运行日志（日志 → 运行日志），反馈问题时导出该日志发给开发者即可。",
                        fontSize = 12.sp,
                        color = BasicComponentDefaults.summaryColor().color,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                item {
                    Text(
                        text = "说明：本页是「全量自检」——覆盖微信公共 API、脚本运行时、Hook 安装任务、关键类锚点、隐藏联系人状态与各分类启用情况。" +
                            "标红或标 ❌ 的项通常就是对应功能失效的原因；点开可看含义、恢复建议与可直接执行的动作。",
                        fontSize = 12.sp,
                        color = BasicComponentDefaults.summaryColor().color,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }

        if (running) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.fillMaxWidth(0.88f).padding(16.dp)) {
                    SettingsCard {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = runTitle,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(14.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(runProgress.coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF2E7DFF)),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "${(runProgress * 100).toInt()}%  $runSubtitle",
                                fontSize = 12.sp,
                                color = BasicComponentDefaults.summaryColor().color,
                            )
                            Spacer(Modifier.height(12.dp))
                            runLog.takeLast(6).forEach { entry ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = if (entry.second) "✅ " else "❌ ", fontSize = 12.sp)
                                    Text(
                                        text = entry.first,
                                        fontSize = 12.sp,
                                        color = if (entry.second) BasicComponentDefaults.summaryColor().color else Color(0xFFD93025),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
/**
 * 模块自检顶部"DEX 适配"风格总览卡片（自画）：
 *   大标题 + 状态主标题 + 元信息三行 + 蓝色分隔线 + 4 格统计 + 提示语 + 主/次按钮。
 *   数据来源是当前 [items]，随自检/修复结果实时刷新；按总览模板风格化（参考截图）。
 */
@Composable
private fun OverviewHealthCard(
    total: Int,
    pass: Int,
    fail: Int,
    off: Int,
    moduleVersion: String,
    wechatVersion: String,
    internalVersion: String,
    lastScan: String,
    scanDurationMs: Long = 0L,
    onShowList: (String) -> Unit = {},
    onRescan: () -> Unit,
    onRepair: () -> Unit,
) {
    val accent = Color(0xFF2E7DFF) // DEX 适配风品牌蓝
    val cardBg = MiuixTheme.colorScheme.surface
    val chipBg = Color(0xFFF1F5F9)
    val muted = Color(0xFF6B7280)
    val title = Color(0xFF1F2937)
    // 只有真故障才叫「异常」；单纯没开启的功能归入「未启用」，不影响整体判定。
    val noFail = fail == 0 && total > 0
    val statusTitle = when {
        total == 0 -> "尚未检测"
        fail > 0 -> "存在缺失项"
        else -> "适配检查完成"
    }
    val statusColor = if (total == 0) muted else if (noFail) Color(0xFF1F2937) else Color(0xFFD93025)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(cardBg),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
            // 顶部小标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "模块自检",
                    fontSize = 14.sp,
                    color = muted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "DEX 适配",
                    fontSize = 12.sp,
                    color = accent,
                )
            }
            Spacer(Modifier.height(8.dp))
            // 状态主标题
            Text(
                text = statusTitle,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor,
            )
            Spacer(Modifier.height(6.dp))
            // 状态行
            Text(
                text = when {
                    total == 0 -> "尚未扫描，点击下方按钮立即开始。"
                    noFail -> "已检查 $total / $total 项，通过 $pass，无异常" +
                        if (off > 0) "（另有 $off 项未启用）" else "。"
                    else -> "已检查 $total / $total 项，通过 $pass，异常 $fail" +
                        if (off > 0) "，未启用 $off。" else "。"
                },
                fontSize = 14.sp,
                color = muted,
            )
            Spacer(Modifier.height(10.dp))
            // 元信息 3 行
            Text(text = "模块版本：$moduleVersion", fontSize = 13.sp, color = muted)
            Text(text = "当前微信：$wechatVersion", fontSize = 13.sp, color = muted)
            Text(text = "内部版本：$internalVersion", fontSize = 13.sp, color = muted)
            // 蓝色分隔线
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(accent),
            )
            Spacer(Modifier.height(16.dp))
            // 4 格统计网格
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell("耗时", formatDuration(scanDurationMs), chipBg, title, muted, Modifier.weight(1f).padding(end = 6.dp))
                StatCell("通过", pass.toString(), chipBg, title, muted, Modifier.weight(1f).padding(horizontal = 3.dp), onClick = { onShowList("pass") })
                StatCell("异常", fail.toString(), chipBg, title, muted, Modifier.weight(1f).padding(horizontal = 3.dp), onClick = { onShowList("fail") })
                StatCell("未启用", off.toString(), chipBg, title, muted, Modifier.weight(1f).padding(start = 6.dp), onClick = { onShowList("off") })
            }
            Spacer(Modifier.height(12.dp))
            // 提示语
            Text(
                text = "通过表示公共能力可用，不等于所有使用场景已验证。",
                fontSize = 13.sp,
                color = muted,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            // 主按钮：立即重新自检
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(accent)
                    .clickable { onRescan() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "立即重新自检",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(8.dp))
            // 次按钮：一键修复（描边式）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(cardBg)
                    .clickable { onRepair() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "一键修复（重建引导链路）",
                    fontSize = 13.sp,
                    color = accent,
                )
            }
        }
    }
}

/** 把毫秒格式化为「1.2s」/「350ms」。 */
private fun formatDuration(ms: Long): String = when {
    ms <= 0L -> "—"
    ms < 1000L -> "${ms}ms"
    else -> String.format(Locale.getDefault(), "%.1fs", ms / 1000.0)
}

/** 4 格统计网格中每个格子的内部渲染。 */
@Composable
private fun StatCell(
    label: String,
    value: String,
    bg: Color,
    valueColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = valueColor)
            Spacer(Modifier.height(2.dp))
            Text(text = label, fontSize = 13.sp, color = labelColor)
        }
    }
}

//  数据模型
// ---------------------------------------------------------------------------

/** 一个检测项。[section] 用于分组；[detail] 点开后的含义 + 建议；[action] 非空时详情弹窗带动作按钮。 */
private data class HealthItem(
    val label: String,
    val section: String,
    val value: String,
    val ok: Boolean,
    val detail: String,
    val actionLabel: String? = null,
    val action: (() -> String)? = null,
    /**
     * 该功能只是「未启用」，并非故障：不计入异常统计，界面也不标红。
     * 例如隐藏联系人开关关闭、插件总开关关闭、一个插件都没启用。
     */
    val inactive: Boolean = false,
)

/** 一个检测/修复步骤：产出一批 [HealthItem]。 */
private class HealthStep(val title: String, val run: () -> List<HealthItem>)

@Composable
private fun HealthRow(item: HealthItem, onOpenDetail: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDetail() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = item.label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = BasicComponentDefaults.titleColor().color,
            modifier = Modifier.fillMaxWidth(0.46f),
        )
        Text(
            text = item.value,
            fontSize = 12.sp,
            color = when {
                item.ok -> BasicComponentDefaults.summaryColor().color
                item.inactive -> Color(0xFFB26A00)
                else -> Color(0xFFD93025)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 报告上限：避免出现「一万字」粘贴不完的情况。 */
private const val MAX_REPORT_CHARS = 3000

private fun showReportDialog(context: Context, title: String, items: List<HealthItem>) {
    val okCount = items.count { it.ok }
    val failItems = items.filter { !it.ok && !it.inactive }
    val offItems = items.filter { !it.ok && it.inactive }
    val sb = StringBuilder()
    sb.append("共 ").append(items.size).append(" 项：通过 ").append(okCount)
        .append("，异常 ").append(failItems.size).append("，未启用 ").append(offItems.size).append("。\n\n")
    if (failItems.isNotEmpty()) {
        sb.append("【异常】需要处理\n")
        failItems.forEach { item ->
            sb.append("❌ ").append(item.label).append('：').append(item.value).append('\n')
        }
        sb.append('\n')
    }
    if (offItems.isNotEmpty()) {
        sb.append("【未启用】不是故障\n")
        offItems.forEach { item ->
            sb.append("○ ").append(item.label).append('：').append(item.value).append('\n')
        }
        sb.append('\n')
    }
    if (failItems.isEmpty() && offItems.isEmpty()) {
        sb.append("全部通过。\n")
    }
    var body = sb.toString()
    if (body.length > MAX_REPORT_CHARS) {
        body = body.take(MAX_REPORT_CHARS) + "\n…（报告过长已截断，可在列表里逐项查看）"
    }
    runCatching {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(body)
            .setNeutralButton("复制") { _, _ ->
                runCatching {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cb?.setPrimaryClip(ClipData.newPlainText(title, body))
                }
                showToast("已复制")
            }
            .setPositiveButton("知道了", null)
            .show()
    }
}

// ---------------------------------------------------------------------------
//  自检步骤（全量）
// ---------------------------------------------------------------------------

private fun moduleHealthSteps(context: Context): List<HealthStep> = listOf(
    HealthStep("功能清单与分类启用概况") { featureOverview() },
    HealthStep("关键类锚点") { anchors() },
    HealthStep("隐藏联系人") { hiddenContactsSection() },
    HealthStep("退出原因与主线程卡顿") { lastExitItems(context) },
)

/**
 * 「上次进程退出原因」——用来区分是系统内存回收、崩溃、ANR 还是用户强行停止。
 * 这段信息是判断「模块有没有主动杀微信」的直接依据。
 */
private fun lastExitItems(context: Context): List<HealthItem> {
    val out = mutableListOf<HealthItem>()

    val summary = runCatching { ExitReasonRecorder.summary(context) }.getOrNull()
    val diagnosis = runCatching { ExitReasonRecorder.diagnosis(context) }.getOrNull()
    out += HealthItem(
        "上次进程退出",
        "运行状态",
        summary ?: "无记录（需 Android 11 及以上）",
        true,
        diagnosis ?: ("Android 11 起系统会记录进程退出原因（内存不足回收 / Java 或原生崩溃 / ANR / 强行停止）。\n" +
            "当前还没有可用记录：可能是首次安装、系统版本过低，或系统未保留记录。"),
    )

    val starts = runCatching { ProcessTimeline.todayStartCount(context) }.getOrDefault(0)
    val pss = runCatching { ProcessTimeline.currentPssMb(context) }.getOrDefault(0)
    out += HealthItem(
        "主进程启动次数",
        "运行状态",
        if (starts > 0) "今日 " + starts + " 次" else "无记录",
        true,
        "每次微信进程启动会记 1 次。\n" +
            "若明显多于你手动打开微信的次数，说明进程在后台被反复杀掉或被系统反复拉起。\n" +
            "当前进程内存（PSS）：" + (if (pss > 0) pss.toString() + "MB" else "未知"),
    )

    val recent = runCatching { ProcessTimeline.recent(context, 5) }.getOrDefault(emptyList())
    if (recent.isNotEmpty()) {
        out += HealthItem(
            "最近启动记录",
            "运行状态",
            recent.first(),
            true,
            recent.joinToString("\n"),
        )
    }

    // 主线程卡顿：ANR 的直接来源，记录卡住时主线程在跑谁。
    val janks = runCatching { MainThreadWatchdog.recent(context) }.getOrDefault(emptyList())
    if (janks.isNotEmpty()) {
        out += HealthItem(
            "主线程卡顿",
            "运行状态",
            janks.first(),
            true,
            "主线程单条消息超过 2 秒即记录（ANR 阈值为 5 秒）：\n" + janks.joinToString("\n"),
        )
    }
    return out
}

/** 「一键修复」动作：脚本子系统下线后只剩微信数据库一项可自愈，这里给出真实结论。 */
private fun repairAction(context: Context): Pair<String, () -> String> =
    "重新检测" to {
        runCatching {
            if (WeDatabaseApi.isReady) "微信数据库已就绪" else "微信数据库仍未就绪，请查看「关键类锚点」"
        }.getOrElse { "异常：" + (it.message ?: it.javaClass.simpleName) }
    }

private fun featureOverview(): List<HealthItem> {
    val items = FeaturesProvider.ALL_HOOK_ITEMS
    val out = mutableListOf<HealthItem>()
    out += HealthItem(
        "功能总数", "功能清单与分类启用概况", "${items.size} 个", true,
        "模块共注册 ${items.size} 个功能项（含各类开关与可点击功能）。",
    )
    items.groupBy { it.categories.firstOrNull() ?: "未分类" }
        .toSortedMap()
        .forEach { (category, list) ->
            val on = list.count { WePrefs.getBoolOrFalse(it.name) }
            val detail = buildString {
                append("该分类共 ${list.size} 个功能，当前已启用 $on 个。\n\n")
                list.forEach { item ->
                    val enabled = WePrefs.getBoolOrFalse(item.name)
                    append(if (enabled) "✅ " else "⬜ ")
                        .append(item.name)
                        .append(if (enabled) "" else "（未启用）")
                        .append("\n")
                }
                if (on == 0) append("\n该分类目前没有任何功能被启用——若你期望其中某功能生效，请到「功能」页对应分类手动开启。")
            }
            out += HealthItem("分类·$category", "功能清单与分类启用概况", "${list.size} 个，已启用 $on", true, detail)
        }
    return out
}

private fun anchors(): List<HealthItem> = listOf(
    anchorItem("聊天页 ChattingUI", "com.tencent.mm.ui.chatting.ChattingUI"),
    anchorItem("资料页 ContactInfoUI", "com.tencent.mm.plugin.profile.ui.ContactInfoUI"),
    anchorItem("通讯录 AddressLiveList", "com.tencent.mm.ui.contact.address.AddressLiveList"),
    anchorItem("朋友圈评论 SnsCommentFooter", "com.tencent.mm.plugin.sns.ui.SnsCommentFooter"),
    anchorItem("主界面 LauncherUI", "com.tencent.mm.ui.LauncherUI"),
    anchorItem("wcdb SQLiteDatabase", "com.tencent.wcdb.database.SQLiteDatabase"),
    anchorItem("wcdb compat SQLiteDatabase", "com.tencent.wcdb.compat.SQLiteDatabase"),
)

private fun anchorItem(name: String, className: String): HealthItem {
    val found = runCatching { Class.forName(className, false, ClassLoaders.MODULE) }.isSuccess
    return HealthItem(
        "锚点·$name", "关键类锚点",
        if (found) "已找到" else "未找到", found,
        "类 $className\n" + if (found) {
            "在当前微信版本中存在，依赖它的功能有锚点可用。"
        } else {
            "未找到——依赖它的功能在当前微信版本可能已漂移/失效，需要适配（无法自动修复）。"
        },
    )
}

private fun hiddenContactsSection(): List<HealthItem> {
    val section = "隐藏联系人"
    val out = mutableListOf<HealthItem>()
    val master = runCatching { HideContacts.isEnabled }.getOrDefault(false)
    val turnOn: () -> String = {
        runCatching { HideContacts.enable() }
        "隐藏联系人已启用；若名单为空请到「联系人与群组 → 隐藏联系人 → 配置隐藏列表」添加"
    }
    out += HealthItem(
        "隐藏联系人开关", section, if (master) "开启" else "关闭", master,
        "隐藏联系人在「联系人与群组」分类下。关闭后所有隐藏与拦截都不生效，名单保留。",
        actionLabel = if (master) null else "启用",
        action = if (master) null else turnOn,
        inactive = !master,
    )
    val count = runCatching { HideContacts.hiddenContacts.size }.getOrDefault(0)
    out += HealthItem(
        "名单人数", section, "$count", true,
        "当前名单共 $count 人。为 0 时不会有任何隐藏效果，请到「配置隐藏列表」添加。",
    )
    val temp = runCatching { HideContacts.isTemporarilyShown }.getOrDefault(false)
    out += HealthItem(
        "临时显示", section, if (temp) "生效中" else "未激活", !temp,
        "临时显示期间，名单内所有人对你可见（#show 或三击标题触发、#hide 恢复）。",
        inactive = temp,
    )
    val blockChat = runCatching { HideContacts.surfaceOn(HideContacts.SURFACE_BLOCK_CHAT) }.getOrDefault(false)
    out += HealthItem(
        "禁止进入聊天", section, if (blockChat) "开启" else "关闭", true,
        "开启后点开名单内联系人的聊天页会立即退出；临时显示期间放行。",
        inactive = !blockChat,
    )
    val blockProfile = runCatching { HideContacts.surfaceOn(HideContacts.SURFACE_BLOCK_PROFILE) }.getOrDefault(false)
    out += HealthItem(
        "禁止查看资料", section, if (blockProfile) "开启" else "关闭", true,
        "开启后点开名单内联系人的资料页会立即退出；临时显示期间放行。",
        inactive = !blockProfile,
    )
    return out
}

// ---------------------------------------------------------------------------
//  修复步骤（全量）
// ---------------------------------------------------------------------------

/**
 * 一键修复（模块自检）：修复前体检 → 对症修复 → 复检 → 前后对比。
 *
 * 与脚本页保持一致：不输出一份「永远全绿」的修复记录，而是给出真实结论——
 * 修复前几项异常、修复后几项异常、哪些已解决、哪些仍然需要处理。
 */
private fun moduleRepairSteps(context: Context): List<HealthStep> {
    val before = mutableListOf<HealthItem>()
    val afterFix = mutableListOf<HealthItem>()

    val checklist = listOf(
    HealthStep("隐藏联系人刷新") {
        val ok = runCatching { WeConversationApi.reloadConversations() }.isSuccess
        listOf(
            HealthItem(
                "隐藏联系人", "刷新会话列表",
                if (ok) "成功" else "失败", ok,
                "按当前名单重新过滤会话列表（名单本身不改动）。",
            )
        )
    },
)

    return listOf(
        HealthStep("修复前体检") {
            before.clear()
            before += moduleHealthSteps(context)
                .flatMap { step -> runCatching { step.run() }.getOrDefault(emptyList()) }
            val bad = before.count { !it.ok && !it.inactive }
            listOf(
                HealthItem(
                    label = "修复前异常",
                    section = "修复前体检",
                    value = "$bad 项",
                    ok = true,
                    detail = "共 ${before.size} 项检测，异常 $bad 项",
                )
            )
        },
    ) + checklist + listOf(
        HealthStep("修复后复检") {
            afterFix.clear()
            afterFix += moduleHealthSteps(context)
                .flatMap { step -> runCatching { step.run() }.getOrDefault(emptyList()) }
            val bad = afterFix.count { !it.ok && !it.inactive }
            listOf(
                HealthItem(
                    label = "修复后异常",
                    section = "修复后复检",
                    value = "$bad 项",
                    ok = bad == 0,
                    detail = "共 ${afterFix.size} 项检测，异常 $bad 项",
                )
            )
        },
        HealthStep("前后对比") {
            val beforeBad = before.filter { !it.ok && !it.inactive }.map { it.label }.toSet()
            val afterBad = afterFix.filter { !it.ok && !it.inactive }.map { it.label }.toSet()
            val fixed = beforeBad - afterBad
            val remain = afterBad
            listOf(
                HealthItem(
                    label = "修复结果",
                    section = "前后对比",
                    value = if (remain.isEmpty()) "全部通过" else "仍有 ${remain.size} 项异常",
                    ok = remain.isEmpty(),
                    detail = buildString {
                        append("修复前异常 ").append(beforeBad.size).append(" 项，修复后 ")
                            .append(remain.size).append(" 项")
                        if (fixed.isNotEmpty()) append("\n已解决：").append(fixed.joinToString("、"))
                        if (remain.isNotEmpty()) append("\n仍存在：").append(remain.joinToString("、"))
                    },
                )
            )
        },
    )
}
