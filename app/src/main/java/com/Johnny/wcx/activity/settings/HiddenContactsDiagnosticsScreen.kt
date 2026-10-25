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
import com.Johnny.wcx.features.items.contacts.HideContacts
import com.Johnny.wcx.features.items.contacts.hidecontacts.BlockHiddenChatSurface
import com.Johnny.wcx.features.items.contacts.hidecontacts.BlockHiddenProfileSurface
import com.Johnny.wcx.utils.android.showToast
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.ui.miuix.ActionRow
import h.Hchat.ui.miuix.InsetDivider
import h.Hchat.ui.miuix.PageScaffold
import h.Hchat.ui.miuix.SettingsCard
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
 * 密友功能诊断页。
 *
 * 密友的全部隐藏面已并入 `HideContacts`（联系人与群组 → 隐藏联系人）后的统一实现，
 * 本页只做「状态读取 + 锚点探测 + 一键修复」，不持有任何隐藏逻辑：
 * - 逐项显示总开关 / 名单 / 临时显示 / 各拦截开关的真实状态；
 * - 用类名探测关键锚点是否在当前微信版本存在，区分「开关没开」与「锚点漂移」；
 * - 一键修复：开总开关、启用功能、推进 Hook 安装阶段、刷新会话列表。
 */
internal data class DiagRow(
    val label: String,
    val section: String,
    val value: String,
    val ok: Boolean,
    val detail: String,
    val actionLabel: String? = null,
    val action: (() -> String)? = null,
)

private class DiagStep(val title: String, val run: () -> List<DiagRow>)

@Composable
fun HiddenContactsDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    var rows by remember { mutableStateOf(listOf<DiagRow>()) }
    var lastScan by remember { mutableStateOf("尚未检测") }
    var running by remember { mutableStateOf(false) }
    var runTitle by remember { mutableStateOf("") }
    var runSubtitle by remember { mutableStateOf("") }
    var runProgress by remember { mutableFloatStateOf(0f) }
    var runLog by remember { mutableStateOf(listOf<Pair<String, Boolean>>()) }

    fun launchScan(
        title: String,
        steps: List<DiagStep>,
        showOverlay: Boolean,
        reportTitle: String?,
        replaceRows: Boolean,
    ) {
        if (running) return
        scope.launch {
            running = showOverlay
            runLog = emptyList()
            runProgress = 0f
            runTitle = title
            runSubtitle = ""
            val verbose = showOverlay || reportTitle != null
            if (verbose) SelfCheckLog.start(title, steps.size)
            val collected = mutableListOf<DiagRow>()
            steps.forEachIndexed { index, step ->
                if (showOverlay) runSubtitle = step.title
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { step.run() }.getOrElse { error ->
                        listOf(
                            DiagRow(
                                label = step.title,
                                section = step.title,
                                value = "检测异常",
                                ok = false,
                                detail = "执行「${step.title}」时抛出异常：\n" + error.toString(),
                            )
                        )
                    }
                }
                collected += outcome
                if (verbose) {
                    outcome.forEach { row -> SelfCheckLog.step(row.ok, row.label, row.value) }
                }
                if (showOverlay) {
                    runLog = runLog + (step.title to outcome.all { it.ok })
                    runProgress = (index + 1).toFloat() / steps.size.toFloat().coerceAtLeast(1f)
                    delay(120)
                }
            }
            if (replaceRows) rows = collected
            SelfCheckLog.end(title, collected.count { it.ok }, collected.size)
            running = false
            lastScan = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (reportTitle != null) showDiagReport(context, reportTitle, collected)
        }
    }

    LaunchedEffect(Unit) {
        launchScan("密友功能诊断", diagSteps(), showOverlay = false, reportTitle = null, replaceRows = true)
    }

    fun openDetail(row: DiagRow) {
        val builder = AlertDialog.Builder(context)
            .setTitle(row.label)
            .setMessage("状态：" + row.value + "\n\n" + row.detail)
            .setNegativeButton("关闭", null)
        val act = row.action
        if (act != null) {
            builder.setPositiveButton(row.actionLabel ?: "执行") { _, _ ->
                scope.launch {
                    val msg = withContext(Dispatchers.IO) {
                        runCatching { act() }.getOrElse { "执行失败：" + (it.message ?: it.javaClass.simpleName) }
                    }
                    showToast(msg)
                    launchScan("密友功能诊断", diagSteps(), showOverlay = false, reportTitle = null, replaceRows = true)
                }
            }
        }
        builder.setNeutralButton("复制") { _, _ ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(row.label, row.label + "：" + row.value + "\n" + row.detail))
            showToast("已复制该项")
        }
        builder.show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PageScaffold(
            title = "密友功能诊断",
            largeTitle = "密友功能诊断",
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
                        text = "最后检测：$lastScan　·　点击任意一项可查看详情",
                        fontSize = 12.sp,
                        color = BasicComponentDefaults.summaryColor().color,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                rows.groupBy { it.section }.forEach { (section, list) ->
                    item { SmallTitle(modifier = Modifier.padding(top = 6.dp), text = section) }
                    item {
                        SettingsCard {
                            list.forEachIndexed { index, row ->
                                if (index > 0) InsetDivider()
                                DiagItemRow(row) { openDetail(row) }
                            }
                        }
                    }
                }

                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "检测与修复") }
                item {
                    SettingsCard {
                        ActionRow("开始检测", "逐项重新扫描开关状态与关键锚点，带进度条") {
                            launchScan("密友功能诊断", diagSteps(), showOverlay = true, reportTitle = "密友诊断结果", replaceRows = true)
                        }
                        InsetDivider()
                        ActionRow("一键修复", "开启总开关 / 启用隐藏联系人 / 推进 Hook 阶段 / 刷新会话列表") {
                            launchScan("一键修复", repairSteps(), showOverlay = true, reportTitle = "修复结果", replaceRows = false)
                        }
                        InsetDivider()
                        ActionRow("复制诊断信息", "复制到剪贴板，便于反馈") {
                            val text = if (rows.isEmpty()) "（尚无诊断结果）"
                            else rows.joinToString("\n") { "${it.label}：${it.value}" }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("WCX 隐藏联系人诊断", text))
                            showToast("诊断信息已复制")
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
                        text = "说明：密友的会话/通讯录/标签/搜索/朋友圈/通知/通话等隐藏面已统一由「隐藏联系人」实现，" +
                            "本页只反映它的真实状态。若某开关显示「开启」却仍能看到，优先看下面「锚点自检」里标红的项——" +
                            "那通常意味着当前微信版本的类/方法已漂移，需要适配而不是重复开关。",
                        fontSize = 12.sp,
                        color = BasicComponentDefaults.summaryColor().color,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }

        if (running) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
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
                                        color = if (entry.second) BasicComponentDefaults.summaryColor().color
                                        else Color(0xFFD93025),
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
//  步骤模型
// ---------------------------------------------------------------------------

private fun diagSteps(): List<DiagStep> = listOf(
    DiagStep("总开关与名单") { masterRows() },
    DiagStep("隐藏与拦截") { surfaceRows() },
    DiagStep("锚点自检") { anchorRows() },
)

private fun repairSteps(): List<DiagStep> = listOf(
    DiagStep("开启总开关与功能") {
        val master = runCatching { Preferences.hiddenContactsMasterEnabled = true }.isSuccess
        val enabled = runCatching { HideContacts.isEnabled = true }.isSuccess
        listOf(
            DiagRow(
                "总开关与功能", "修复",
                if (master && enabled) "已开启" else "部分失败", master && enabled,
                "密友功能总开关 = ${if (master) "开启" else "开启失败"}；" +
                    "隐藏联系人 = ${if (enabled) "启用" else "启用失败"}。",
            )
        )
    },
    DiagStep("刷新会话列表") {
        val ok = runCatching { WeConversationApi.reloadConversations() }.isSuccess
        listOf(
            DiagRow(
                "刷新会话列表", "修复", if (ok) "成功" else "失败", ok,
                "重新加载微信会话列表，让隐藏/恢复立即生效。",
            )
        )
    },
    DiagStep("重排 Hook 安装任务") {
        val ok = runCatching {
            DexInstallScheduler.ensureWarmupReady()
            DexInstallScheduler.retryAllPending()
        }.isSuccess
        listOf(
            DiagRow(
                "Hook 安装任务", "修复",
                "阶段=" + DexInstallScheduler.readyLevelName() + "，待装 " + DexInstallScheduler.pendingCount() + " 个",
                ok,
                "强制推进到 WARMUP 并重排全部 Hook 安装任务（隐藏面依赖的 DexKit 钩子都在这里装）。",
            )
        )
    },
    DiagStep("重新读取名单") {
        val count = runCatching { HideContacts.hiddenContacts.size }.getOrDefault(0)
        listOf(
            DiagRow(
                "密友名单", "修复", "当前 $count 人", true,
                "重新读取当前名单。若为 0，请到「密友功能 → 隐藏联系人 → 配置隐藏列表」添加。",
            )
        )
    },
)

private fun masterRows(): List<DiagRow> {
    val out = mutableListOf<DiagRow>()

    val master = runCatching { Preferences.hiddenContactsMasterEnabled }.getOrDefault(false)
    out += DiagRow(
        "密友功能总开关", "总开关与名单", if (master) "开启" else "关闭", master,
        "分类总开关。关闭后本分类下所有功能都不加载（各开关状态保留）。",
        actionLabel = if (master) null else "开启",
        action = if (master) null else {
            {
                Preferences.hiddenContactsMasterEnabled = true
                "总开关已开启"
            }
        },
    )

    val enabled = runCatching { HideContacts.isEnabled }.getOrDefault(false)
    out += DiagRow(
        "隐藏联系人功能", "总开关与名单", if (enabled) "启用" else "未启用", enabled,
        "所有隐藏面的实际执行者。未启用时即使名单非空也不会有任何隐藏效果。",
        actionLabel = if (enabled) null else "启用",
        action = if (enabled) null else {
            {
                HideContacts.isEnabled = true
                "隐藏联系人已启用"
            }
        },
    )

    val count = runCatching { HideContacts.hiddenContacts.size }.getOrDefault(0)
    out += DiagRow(
        "名单人数", "总开关与名单", "$count", count > 0,
        if (count > 0) "当前名单共 $count 人。"
        else "名单为空，不会有任何隐藏效果。请到「配置隐藏列表」添加。",
    )

    val temp = runCatching { HideContacts.isTemporarilyShown }.getOrDefault(false)
    out += DiagRow(
        "临时显示", "总开关与名单", if (temp) "生效中" else "未激活", !temp,
        "临时显示期间名单内所有人对你可见。触发方式：聊天框输入 #show、主页搜索框输入指令、三击主页标题；" +
            "恢复方式：#hide 或到期自动恢复。",
    )
    return out
}

private fun surfaceRows(): List<DiagRow> {
    val out = mutableListOf<DiagRow>()

    val blockChat = runCatching { HideContacts.surfaceOn(HideContacts.SURFACE_BLOCK_CHAT) }.getOrDefault(false)
    out += DiagRow(
        "禁止进入聊天", "隐藏与拦截", if (blockChat) "开启" else "关闭", true,
        "开启后点开名单内联系人的聊天页会立即退出；临时显示期间放行。" +
            if (blockChat) "" else "\n当前关闭——这是「默认关闭」的按需项，不是故障。",
        actionLabel = if (blockChat) "关闭" else "开启",
        action = {
            val next = !blockChat
            BlockHiddenChatSurface.applyToggle(next)
            if (next) "已开启禁止进入聊天" else "已关闭禁止进入聊天"
        },
    )

    val blockProfile = runCatching { HideContacts.surfaceOn(HideContacts.SURFACE_BLOCK_PROFILE) }.getOrDefault(false)
    out += DiagRow(
        "禁止查看资料", "隐藏与拦截", if (blockProfile) "开启" else "关闭", true,
        "开启后点开名单内联系人的资料页会立即退出；临时显示期间放行。" +
            if (blockProfile) "" else "\n当前关闭——这是「默认关闭」的按需项，不是故障。",
        actionLabel = if (blockProfile) "关闭" else "开启",
        action = {
            val next = !blockProfile
            BlockHiddenProfileSurface.applyToggle(next)
            if (next) "已开启禁止查看资料" else "已关闭禁止查看资料"
        },
    )

    val autoReject = runCatching { HideContacts.surfaceOn(HideContacts.SURFACE_AUTO_REJECT_VOIP) }.getOrDefault(false)
    out += DiagRow(
        "自动拒绝音视频通话", "隐藏与拦截", if (autoReject) "开启" else "关闭", true,
        "开启时对名单内联系人的来电立即发送拒接；关闭时仅隐藏来电，对方会响到超时。" +
            if (autoReject) "" else "\n当前关闭——按需项，不是故障。",
    )

    val triple = runCatching { HideContacts.tripleClickTitle }.getOrDefault(false)
    out += DiagRow(
        "三击标题切换显隐", "隐藏与拦截", if (triple) "开启" else "关闭", true,
        "连续三击主页顶部标题栏，可临时显示或恢复隐藏。当前：" + if (triple) "开启" else "关闭（按需项）。",
    )

    val searchCmd = runCatching { HideContacts.searchCommandText }.getOrDefault("#wm#")
    val searchMin = runCatching { HideContacts.searchCommandMinutes }.getOrDefault(30)
    out += DiagRow(
        "搜索框临时显示指令", "隐藏与拦截", searchCmd, searchCmd.isNotBlank(),
        "在微信主页搜索框输入「$searchCmd」即可临时显示 $searchMin 分钟，到期自动恢复隐藏。",
    )

    val dbReady = runCatching { WeDatabaseApi.isReady }.getOrDefault(false)
    out += DiagRow(
        "微信数据库", "隐藏与拦截", if (dbReady) "已就绪" else "未就绪", dbReady,
        "会话列表/通讯录/标签的过滤都依赖数据库查询改写；未就绪时上述隐藏面不生效，等微信启动完成后重试即可。",
    )
    return out
}

private fun anchorRows(): List<DiagRow> {
    val out = mutableListOf<DiagRow>()
    listOf(
        "聊天页" to "com.tencent.mm.ui.chatting.ChattingUI",
        "资料页" to "com.tencent.mm.plugin.profile.ui.ContactInfoUI",
        "通讯录" to "com.tencent.mm.ui.contact.address.AddressLiveList",
        "wcdb" to "com.tencent.wcdb.database.SQLiteDatabase",
    ).forEach { (label, className) ->
        val found = runCatching {
            Class.forName(className, false, HideContacts::class.java.classLoader)
        }.isSuccess
        out += DiagRow(
            label, "锚点自检", if (found) "已找到" else "未找到", found,
            "类 $className\n" + if (found) {
                "在当前微信版本中存在，依赖它的拦截可用。"
            } else {
                "未找到——当前微信版本里该类已改名/挪包，依赖它的功能会退化，需要适配（无法自动修复）。"
            },
        )
    }
    return out
}

// ---------------------------------------------------------------------------
//  UI
// ---------------------------------------------------------------------------

@Composable
private fun DiagItemRow(row: DiagRow, onOpenDetail: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDetail() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = if (row.ok) "✅" else "❌",
            fontSize = 13.sp,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = row.value,
                fontSize = 12.sp,
                color = if (row.ok) BasicComponentDefaults.summaryColor().color else Color(0xFFD93025),
            )
        }
    }
}

/** 结果弹窗统一走纯文本对话框（避免自定义 View 测高为 0 显示空白）。 */
private fun showDiagReport(context: Context, title: String, rows: List<DiagRow>) {
    val failed = rows.count { !it.ok }
    val body = rows.joinToString("\n") { "${if (it.ok) "✅" else "❌"} ${it.label}：${it.value}" }
    AlertDialog.Builder(context)
        .setTitle("$title（异常 $failed 项）")
        .setMessage(body.ifBlank { "（无结果）" })
        .setPositiveButton("知道了", null)
        .setNeutralButton("复制") { _, _ ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(title, body))
            showToast("已复制")
        }
        .show()
}
