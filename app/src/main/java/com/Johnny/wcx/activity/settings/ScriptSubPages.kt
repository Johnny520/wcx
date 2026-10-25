package com.Johnny.wcx.activity.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.hchat.HchatScriptBootstrap
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.android.showToast
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.runtime.WeChatDatabaseApi
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.hooks.items.script.ScriptPluginSettings
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.miuix.ActionRow
import h.Hchat.ui.miuix.InsetDivider
import h.Hchat.ui.miuix.PageScaffold
import h.Hchat.ui.miuix.PluginMarketUi
import h.Hchat.ui.miuix.MarketServerSettingCard
import h.Hchat.ui.miuix.ScriptPluginManagerTabPage
import h.Hchat.ui.miuix.SettingsCard
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.preferences.WePrefs
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.script.market.PluginMarketSettings
import com.Johnny.wcx.utils.fs.KnownPaths
import h.Hchat.hooks.core.DexInstallScheduler
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 在线插件市场（Hchat 原版 UI），整页跳转。 */
@Composable
fun ScriptMarketScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        PluginMarketUi.ScriptPluginMarketPage(context = context, onBack = onBack)
    }
}

/** 本地插件管理，整页跳转。 */
@Composable
fun ScriptManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        ScriptPluginManagerTabPage(context = context, onBack = onBack)
    }
}

// ---------------------------------------------------------------------------
// 脚本自检 / 一键修复
// ---------------------------------------------------------------------------

private data class ScriptSelfCheckItem(
    val name: String,
    val ok: Boolean,
    val detail: String,
    /** 失败时是否可由「一键修复」自愈；false 表示需要开发者适配（如微信版本锚点漂移）。 */
    val fixable: Boolean,
)

/** 失败归因：决定「一键修复」能不能解决，以及给用户什么建议。 */
private enum class FixKind {
    /** 缺目录 / 开关未开 / 阶段未推进 / 重试耗尽 / 插件未加载：修复流程可自愈。 */
    SELF_HEAL,

    /** 微信版本锚点未命中 / 关键类缺失：只有更新模块或补特征包才能解决。 */
    NEEDS_ADAPTER,
}

/** 一个可执行的诊断 / 修复步骤：label 描述；kind 为失败归因；run 返回 (是否通过, 说明)。 */
private class ScriptRunStep(
    val label: String,
    val kind: FixKind = FixKind.SELF_HEAL,
    val run: () -> Pair<Boolean, String>,
)

/**
 * 同步执行全部自检项。
 * 供「一键修复」在修复前后各跑一次做对比，也避免一处结论两套逻辑。
 */
private fun runScriptSelfCheck(context: Context): List<ScriptSelfCheckItem> =
    scriptSelfCheckSteps(context).map { step ->
        val outcome = runCatching { step.run() }
            .getOrElse { false to ("执行异常：" + (it.message ?: it.javaClass.simpleName)) }
        ScriptSelfCheckItem(
            name = step.label,
            ok = outcome.first,
            detail = outcome.second,
            fixable = step.kind == FixKind.SELF_HEAL,
        )
    }

/** 逐项自检步骤：覆盖脚本运行全链路（目录 / 开关 / 引导 / 数据库 API / DexKit / Hook 任务 / 插件 / ABI / 服务器）。 */
private fun apiCheck(get: () -> Any?): Pair<Boolean, String> {
    val ok = runCatching { get() }.getOrNull() != null
    return ok to (if (ok) "已解析" else "未解析：DexKit 未完成或当前微信版本未适配")
}

/**
 * 全量自检步骤：覆盖脚本运行全链路——
 * 目录 / 总开关 / 引导 / 微信公共 API 全套 / 数据库取库 / DexKit / 就绪阶段 /
 * Hook 任务逐项 / 脚本 Hook 注册 / ABI / 本地插件逐项 / 插件加载错误 / 市场 / 环境。
 */
private fun scriptSelfCheckSteps(context: Context): List<ScriptRunStep> {
    val steps = mutableListOf<ScriptRunStep>()

    steps += ScriptRunStep("脚本目录") {
        val dir = runCatching { ScriptPluginRuntime.scriptDir(context) }.getOrNull()
        val ok = dir != null && dir.isDirectory
        ok to (dir?.absolutePath ?: "无法获取脚本目录")
    }
    steps += ScriptRunStep("插件总开关") {
        val on = runCatching {
            HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME)
                .getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE)
        }.getOrDefault(false)
        on to (if (on) "已开启" else "未开启：插件不会被加载")
    }
    steps += ScriptRunStep("引导链路") {
        val s = runCatching { HchatScriptBootstrap.status }.getOrDefault("未知")
        (s.contains("成功") || s.contains("已安装")) to s
    }
    steps += ScriptRunStep("脚本运行时就绪") {
        val detail = runCatching { ScriptPluginRuntime.scriptApiReadinessDetail() }.getOrNull()
        (detail == null) to (detail ?: "已就绪")
    }

    // —— 微信公共 API 全套 ——
    steps += ScriptRunStep("微信数据库 API") { apiCheck { WeChatApis.database() } }
    steps += ScriptRunStep("微信账号 API") { apiCheck { WeChatApis.account() } }
    steps += ScriptRunStep("微信联系人 API") { apiCheck { WeChatApis.contacts() } }
    steps += ScriptRunStep("微信会话 API") { apiCheck { WeChatApis.conversations() } }
    steps += ScriptRunStep("微信消息 API") { apiCheck { WeChatApis.messages() } }
    steps += ScriptRunStep("微信群聊 API") { apiCheck { WeChatApis.chatrooms() } }
    steps += ScriptRunStep("微信版本 API") {
        val v = runCatching { WeChatApis.version()?.current()?.displayVersion() }.getOrNull()
        (v?.isNotBlank() == true) to (v ?: "未解析：DexKit 未完成或当前微信版本未适配")
    }
    steps += ScriptRunStep("微信 API 完整性自检") {
        val issues = runCatching { WeChatApis.diagnostics()?.buildCompatibilityIssues() }.getOrNull().orEmpty()
        (issues.isBlank()) to (if (issues.isBlank()) "全部关键能力就绪" else "缺失：" + issues.replace("\n", " / "))
    }

    // —— 数据库取库链路 ——
    steps += ScriptRunStep("数据库 API 可用") {
        val ok = runCatching { (WeChatApis.database() as? WeChatDatabaseApi)?.isAvailable() == true }.getOrDefault(false)
        ok to (if (ok) "是（已定位 CoreStorage/SqliteDB 类）" else "否（未解析出 CoreStorage 或 SqliteDB 类）")
    }
    steps += ScriptRunStep("数据库对象就绪") {
        val ok = runCatching { (WeChatApis.database() as? WeChatDatabaseApi)?.isReady() == true }.getOrDefault(false)
        ok to (if (ok) "是（已取得 SqliteDB 对象）" else "否（CoreStorage 里找不到 SqliteDB 对象）")
    }
    steps += ScriptRunStep("数据库取库自检") {
        val d = runCatching { (WeChatApis.database() as? WeChatDatabaseApi)?.diagnose() }.getOrNull()
        (d != null) to (d ?: "无（数据库 API 未解析）")
    }

    steps += ScriptRunStep("DexKit 解析") {
        val info = runCatching { HchatScriptBootstrap.dexParseInfo() }.getOrDefault("")
        val ok = info.isNotBlank() && !info.contains("未初始化")
        ok to info.ifBlank { "无信息" }
    }
    steps += ScriptRunStep("就绪阶段") {
        val lvl = runCatching { DexInstallScheduler.readyLevelName() }.getOrDefault("未知")
        lvl.contains("WARMUP") to lvl
    }

    // —— Hook 安装任务：总览 + 逐项 ——
    steps += ScriptRunStep("Hook 安装任务总览") {
        val total = runCatching { DexInstallScheduler.snapshot().size }.getOrDefault(0)
        val pending = runCatching { DexInstallScheduler.pendingCount() }.getOrDefault(0)
        val failed = runCatching { DexInstallScheduler.failedCount() }.getOrDefault(0)
        (total > 0 && pending == 0) to
            ("共 $total 个任务，已装 ${total - pending}，待装 $pending，失败暂停 $failed")
    }
    runCatching { DexInstallScheduler.snapshot() }.getOrDefault(emptyList()).forEach { task ->
        steps += ScriptRunStep(
            "Hook·${task.label}",
            // lastError 里出现「未命中」= DexKit 在当前微信版本上没定位到目标，
            // 这种情况重试多少次都不会成功，必须由开发者补特征 / 适配。
            kind = if (task.lastError?.contains("未命中") == true) FixKind.NEEDS_ADAPTER else FixKind.SELF_HEAL,
        ) {
            task.installed to if (task.installed) {
                "已安装"
            } else {
                buildString {
                    append("待装（第 ").append(task.retryIndex).append(" 次")
                    if (task.paused) append("，已暂停")
                    append("）")
                    if (!task.lastError.isNullOrBlank()) append("｜上次失败：").append(task.lastError)
                }
            }
        }
    }

    steps += ScriptRunStep("脚本 Hook 注册") {
        val n = runCatching { HookRegistry.get().size() }.getOrDefault(0)
        (n > 0) to (if (n > 0) "$n 个" else "0 个：引导链路可能未完成，点「一键修复」重建")
    }
    steps += ScriptRunStep("脚本运行库（ABI）", kind = FixKind.NEEDS_ADAPTER) {
        val ok = runCatching { Class.forName("me.hd.wauxv.plugin.api.callback.PluginCallBack") }.isSuccess
        ok to (if (ok) "已加载" else "关键类缺失：插件会全部加载失败，请更新到最新版本")
    }

    // —— 本地插件：总览 + 逐项 ——
    val plugins = runCatching { ScriptPluginRuntime.listPlugins(context) }.getOrDefault(emptyList())
    val enabledCount = plugins.count {
        runCatching { ScriptPluginRuntime.isPluginEnabled(context, it.id) }.getOrDefault(false)
    }
    steps += ScriptRunStep("本地插件") {
        val noEnabled = plugins.isNotEmpty() && enabledCount == 0
        (!noEnabled) to when {
            plugins.isEmpty() -> "尚未安装任何插件"
            noEnabled -> "已安装 ${plugins.size} 个，但一个都没有启用"
            else -> "已安装 ${plugins.size} 个，已启用 $enabledCount 个"
        }
    }
    plugins.forEach { plugin ->
        steps += ScriptRunStep("插件·${plugin.displayName ?: plugin.id}") {
            if (!plugin.dir.isDirectory) {
                false to "插件目录缺失"
            } else {
                val enabled = runCatching { ScriptPluginRuntime.isPluginEnabled(context, plugin.id) }.getOrDefault(false)
                true to (if (enabled) "已启用" else "未启用")
            }
        }
    }
    steps += ScriptRunStep("插件加载错误") {
        val errors = collectPluginLoadErrors(context)
        errors.isEmpty() to (if (errors.isEmpty()) "无" else errors.joinToString(" ｜ "))
    }

    steps += ScriptRunStep("市场服务器地址") {
        val server = runCatching { PluginMarketSettings.serviceUrl(context) }.getOrNull().orEmpty()
        server.isNotBlank() to server.ifBlank { "为空：在线插件市场不可用" }
    }
    steps += ScriptRunStep("宿主进程") {
        val p = runCatching { TargetProcesses.currentName }.getOrDefault("未知")
        p.isNotBlank() to p
    }
    steps += ScriptRunStep("模块版本") {
        true to "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
    }
    steps += ScriptRunStep("日志目录") {
        val d = runCatching { KnownPaths.moduleData.resolve("logs").toString() }.getOrNull()
        (d != null) to (d ?: "未知")
    }
    return steps
}

/**
 * 一键修复：轻量对症修复 → 复检 → 仍有「可自愈」项才重建引导链路 → 再复检 → 前后对比。
 *
 * 设计要点：
 * - 不无条件重建：重建会重新扫 dex、重装 Hook，代价大，只在自愈项仍失败时才做；
 * - 不对「需要开发者适配」的项反复重试（新版微信锚点漂移重试一百次也不会成功），直接给结论；
 * - 每一步返回真实结果，不再恒为 true，用户能看出到底修没修好。
 */
private fun scriptRepairSteps(context: Context): List<ScriptRunStep> {
    val steps = mutableListOf<ScriptRunStep>()
    var before: List<ScriptSelfCheckItem> = emptyList()
    var afterLight: List<ScriptSelfCheckItem> = emptyList()

    steps += ScriptRunStep("修复前体检") {
        before = runScriptSelfCheck(context)
        val bad = before.count { !it.ok }
        true to "共 ${before.size} 项，异常 $bad 项"
    }
    steps += ScriptRunStep("补建脚本目录") {
        val dir = runCatching { ScriptPluginRuntime.scriptDir(context) }.getOrNull()
        when {
            dir == null -> false to "无法获取脚本目录"
            dir.isDirectory -> true to "目录已存在：${dir.absolutePath}"
            else -> {
                val created = dir.mkdirs() || dir.isDirectory
                created to (if (created) "已创建：${dir.absolutePath}" else "创建失败：${dir.absolutePath}")
            }
        }
    }
    steps += ScriptRunStep("开启插件总开关") {
        val on = runCatching {
            HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME)
                .getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE)
        }.getOrDefault(false)
        if (on) {
            true to "已是开启状态"
        } else {
            runCatching { ScriptPluginRuntime.setGlobalEnabled(context, true) }
                .fold({ true to "已开启" }, { false to ("开启失败：" + it.message.orEmpty()) })
        }
    }
    steps += ScriptRunStep("推进就绪阶段并重排全部 Hook 任务") {
        DexInstallScheduler.ensureWarmupReady()
        DexInstallScheduler.retryAllPending()
        true to ("阶段=" + DexInstallScheduler.readyLevelName() +
            "，待装 " + DexInstallScheduler.pendingCount() + " 个")
    }
    steps += ScriptRunStep("重载已启用插件") {
        val plugins = runCatching { ScriptPluginRuntime.listPlugins(context) }.getOrDefault(emptyList())
        val enabled = plugins.filter {
            runCatching { ScriptPluginRuntime.isPluginEnabled(context, it.id) }.getOrDefault(false)
        }
        if (enabled.isEmpty()) {
            true to "没有已启用的插件需要重载"
        } else {
            val failures = mutableListOf<String>()
            var okCount = 0
            enabled.forEach { plugin ->
                runCatching { ScriptPluginRuntime.reloadPluginAsync(context, plugin.id) }
                    .onSuccess { okCount++ }
                    .onFailure { failures += (plugin.displayName ?: plugin.id) + "：" + (it.message ?: it.javaClass.simpleName) }
            }
            failures.isEmpty() to buildString {
                append("已重载 ").append(okCount).append(" / ").append(enabled.size).append(" 个")
                if (failures.isNotEmpty()) append("，失败：").append(failures.joinToString("、"))
            }
        }
    }
    steps += ScriptRunStep("复检（轻量修复后）") {
        afterLight = runScriptSelfCheck(context)
        val healable = afterLight.count { !it.ok && it.fixable }
        val needsAdapter = afterLight.count { !it.ok && !it.fixable }
        true to ("仍有异常 ${healable + needsAdapter} 项（可自愈 $healable 项，需适配 $needsAdapter 项）")
    }
    steps += ScriptRunStep("重建引导链路（仅在仍有可自愈项时执行）") {
        val healable = afterLight.filter { !it.ok && it.fixable }
        if (healable.isEmpty()) {
            true to "跳过：剩余异常项都属于版本适配问题，重建无效"
        } else {
            val lines = runCatching { HchatScriptBootstrap.reinitializeFully() }
                .getOrElse { listOf("异常：" + (it.message ?: it.javaClass.simpleName)) }
            DexInstallScheduler.ensureWarmupReady()
            DexInstallScheduler.retryAllPending()
            val failed = lines.any { it.contains("失败") || it.contains("异常") }
            (!failed) to lines.joinToString("；")
        }
    }
    steps += ScriptRunStep("最终复检 + 前后对比") {
        val after = runScriptSelfCheck(context)
        val beforeBad = before.filter { !it.ok }.map { it.name }.toSet()
        val afterBad = after.filter { !it.ok }.map { it.name }.toSet()
        val fixed = beforeBad - afterBad
        val remain = afterBad
        val remainText = remain.joinToString("、") { name ->
            val item = after.firstOrNull { it.name == name }
            name + if (item != null && !item.fixable) "（需开发者适配）" else "（可再试一次）"
        }
        (remain.isEmpty()) to buildString {
            append("修复前异常 ").append(beforeBad.size).append(" 项，修复后 ")
                .append(remain.size).append(" 项")
            if (fixed.isNotEmpty()) append("；已解决：").append(fixed.joinToString("、"))
            if (remain.isNotEmpty()) append("；仍存在：").append(remainText) else append("；全部通过")
        }
    }
    steps += ScriptRunStep("脚本运行库（ABI）自检", kind = FixKind.NEEDS_ADAPTER) {
        val ok = runCatching { Class.forName("me.hd.wauxv.plugin.api.callback.PluginCallBack") }.isSuccess
        ok to (if (ok) "已加载" else "关键类缺失：插件无法加载，请更新到最新版本（无法自动修复）")
    }
    return steps
}

private fun showScriptInfoDialog(context: Context, title: String, message: String) {
    runCatching {
        android.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setNeutralButton("复制") { _, _ ->
                runCatching {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText(title, message))
                }
            }
            .setPositiveButton("知道了", null)
            .show()
    }
}



/**
 * 脚本功能诊断页。
 *
 * 目标：脚本开关开不起来 / 脚本不工作时，一眼能看出卡在引导链路的哪一步、微信 API 是否解析、
 * 每个 Hook 安装任务是否装成。诊断 / 修复 / 重新初始化均以进度条 + 逐项推进的方式执行。
 */
@Composable
fun ScriptDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshVersion by remember { mutableStateOf(0) }
    var lastRefresh by remember { mutableStateOf("尚未刷新") }
    val rows = remember(refreshVersion) { collectScriptDiagnostics(context) }
    val scrollBehavior = MiuixScrollBehavior()

    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var runTitle by remember { mutableStateOf("") }
    var runProgress by remember { mutableFloatStateOf(0f) }
    var runSubtitle by remember { mutableStateOf("") }
    var runLog by remember { mutableStateOf(listOf<Pair<String, Boolean>>()) }

    fun startRun(title: String, stepsProvider: () -> List<ScriptRunStep>, reportTitle: String) {
        if (running) return
        running = true
        runTitle = title
        runProgress = 0f
        runSubtitle = "准备中…"
        runLog = emptyList()
        scope.launch {
            val steps = runCatching { stepsProvider() }.getOrElse { emptyList() }
            SelfCheckLog.start(title, steps.size)
            val results = mutableListOf<ScriptSelfCheckItem>()
            steps.forEachIndexed { index, step ->
                runSubtitle = "正在执行：${step.label}"
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { step.run() }
                        .getOrElse { false to ("执行异常：" + (it.message ?: it.javaClass.simpleName)) }
                }
                results += ScriptSelfCheckItem(
                    name = step.label,
                    ok = outcome.first,
                    detail = outcome.second,
                    fixable = step.kind == FixKind.SELF_HEAL,
                )
                SelfCheckLog.step(outcome.first, step.label, outcome.second)
                runLog = runLog + (step.label to outcome.first)
                runProgress = (index + 1).toFloat() / steps.size.toFloat()
                delay(160)
            }
            running = false
            val okCount = results.count { it.ok }
            val sb = StringBuilder()
            sb.append("共 ").append(results.size).append(" 项，通过 ").append(okCount)
                .append("，问题 ").append(results.size - okCount).append("。\n\n")
            results.forEach { item ->
                sb.append(if (item.ok) "✅ " else if (item.fixable) "⚠ " else "❌ ")
                    .append(item.name).append("：").append(item.detail)
                if (!item.ok) {
                    sb.append(if (item.fixable) "（点「一键修复」可尝试解决）" else "（需开发者适配，重试无效）")
                }
                sb.append('\n')
            }
            val healable = results.count { !it.ok && it.fixable }
            val needsAdapter = results.count { !it.ok && !it.fixable }
            if (needsAdapter > 0) {
                sb.append("\n其中 ").append(needsAdapter)
                    .append(" 项是当前微信版本适配问题，一键修复解决不了，请到「日志」页导出运行日志反馈。")
            } else if (healable > 0) {
                sb.append("\n其中 ").append(healable).append(" 项可以点「一键修复」尝试自愈。")
            }
            SelfCheckLog.end(title, okCount, results.size)
            refreshVersion++
            lastRefresh = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            showScriptInfoDialog(context, reportTitle, sb.toString())
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PageScaffold(
            title = "脚本功能诊断",
            largeTitle = "脚本功能诊断",
            scrollBehavior = scrollBehavior,
            onBack = onBack,
        ) { padding ->
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 84.dp,
                ),
            ) {
                item {
                    Text(
                        text = "最后刷新：$lastRefresh",
                        fontSize = 12.sp,
                        color = BasicComponentDefaults.summaryColor().color,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                item {
                    DexScriptOverview(
                        moduleVersion = "${com.Johnny.wcx.BuildConfig.VERSION_NAME} (${com.Johnny.wcx.BuildConfig.VERSION_CODE})",
                        wechatVersion = runCatching { WeChatApis.version()?.current()?.displayVersion() }.getOrNull().orEmpty(),
                        rowsCount = rows.size,
                        lastRefresh = lastRefresh,
                        onRescan = {
                            refreshVersion++
                            lastRefresh = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                            showToast("诊断信息已刷新")
                        }
                    )
                }
                item { SmallTitle(modifier = Modifier.padding(top = 6.dp), text = "运行时状态") }
                item {
                    SettingsCard {
                        rows.forEachIndexed { index, row ->
                            if (index > 0) InsetDivider()
                            val value = row.second
                            val warn = value.contains("✗") || value.contains("❌") ||
                                value.contains("未解析") || value.contains("失败") || value.contains("等待")
                            DiagnosticRow(row.first, value, warn) { refreshVersion++ }
                        }
                    }
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "市场服务器") }
                item { MarketServerSettingCard(context) }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "一键诊断与修复") }
                item {
                    SettingsCard {
                        ActionRow("一键自检", "逐项检查脚本运行全链路，列出真实问题（带进度）") {
                            startRun("一键自检", { scriptSelfCheckSteps(context) }, "脚本自检结果")
                        }
                        InsetDivider()
                        ActionRow("一键修复", "自动处理可自愈的问题（目录 / 开关 / 就绪阶段 / 重试 / 插件），修完自动复检并给出前后对比；需要适配的项会单独标出") {
                            startRun("一键修复", { scriptRepairSteps(context) }, "脚本修复结果")
                        }
                    }
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "诊断工具") }
                item {
                    SettingsCard {
                        ActionRow("刷新诊断信息", "重新读取当前运行时状态 · 上次：$lastRefresh") {
                            refreshVersion++
                            lastRefresh = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                .format(java.util.Date())
                            showToast("诊断信息已刷新")
                        }
                        InsetDivider()
                        ActionRow("复制诊断信息", "把上面的内容复制到剪贴板，便于反馈") {
                            val text = rows.joinToString("\n") { "${it.first}：${it.second}" }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("WCX 脚本诊断", text))
                            showToast("诊断信息已复制")
                        }
                    }
                }
                item {
                    Text(
                        text = "定位过程、失败原因、锚点命中情况都已写入运行日志（日志 → 运行日志）。" +
                            "反馈问题时导出该日志发给开发者即可，这里不再重复展示。",
                        fontSize = 12.sp,
                        color = BasicComponentDefaults.summaryColor().color,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                item {
                    Text(
                        text = "提示：脚本依赖微信公共 API（DexKit 解析数据库/账号/消息等）。" +
                            "若「微信数据库 API」为未解析，通常是 DexKit 预热未完成或当前微信版本未适配。",
                        fontSize = 12.sp,
                        color = BasicComponentDefaults.summaryColor().color,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }

        if (running) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0xCC000000)),
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
                                        .background(androidx.compose.ui.graphics.Color(0xFF2E7DFF)),
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
                                    Text(
                                        text = if (entry.second) "✅ " else "❌ ",
                                        fontSize = 12.sp,
                                    )
                                    Text(
                                        text = entry.first,
                                        fontSize = 12.sp,
                                        color = if (entry.second) BasicComponentDefaults.summaryColor().color
                                        else androidx.compose.ui.graphics.Color(0xFFD93025),
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


@Composable
private fun DiagnosticRow(label: String, value: String, warn: Boolean, onActionDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val action = scriptDiagAction(context, label)
                val builder = android.app.AlertDialog.Builder(context)
                    .setTitle(label)
                    .setMessage(value + "\n\n" + scriptDiagHint(label))
                    .setNegativeButton("关闭", null)
                if (action != null) {
                    builder.setPositiveButton(action.first) { _, _ ->
                        scope.launch {
                            val msg = withContext(Dispatchers.IO) {
                                runCatching { action.second() }
                                    .getOrElse { "执行失败：" + (it.message ?: it.javaClass.simpleName) }
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            onActionDone()
                        }
                    }
                }
                builder.show()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = BasicComponentDefaults.titleColor().color,
            modifier = Modifier.fillMaxWidth(0.42f),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = if (warn) androidx.compose.ui.graphics.Color(0xFFD93025)
            else BasicComponentDefaults.summaryColor().color,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 该行可执行的动作（仅可修复的项返回；点开详情时显示为按钮）。 */
private fun scriptDiagAction(context: Context, label: String): Pair<String, () -> String>? = when {
    label.contains("插件总开关") -> {
        val on = runCatching {
            HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME)
                .getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE)
        }.getOrDefault(false)
        if (on) null else "开启总开关" to {
            runCatching { ScriptPluginRuntime.setGlobalEnabled(context, true) }
                .fold({ "插件总开关已开启" }, { "开启失败：" + it.message.orEmpty() })
        }
    }
    label.contains("Hook 安装任务") || label.contains("·") -> "重排重试" to {
        DexInstallScheduler.ensureWarmupReady()
        DexInstallScheduler.retryAllPending()
        "已重排重试，当前待装 " + DexInstallScheduler.pendingCount() + " 个"
    }
    label.contains("未就绪原因") || label.contains("DexKit") -> "一键修复" to {
        HchatScriptBootstrap.reinitializeFully().joinToString("；")
    }
    else -> null
}

/** 每行诊断点开后的说明（含义 + 处理建议）。 */
private fun scriptDiagHint(label: String): String = when {
    label.contains("插件总开关") ->
        "总开关关着时任何插件都不会加载。开启后若立刻失败，多半是「微信联系人数据库尚未就绪」——看「未就绪原因」那一行。"
    label.contains("未就绪原因") ->
        "这是「插件总开关」打不开时最直接的卡点。显示「无（已就绪）」却仍开不了，重启微信再试；否则点「一键修复」。"
    label.contains("数据库") ->
        "脚本读联系人/群聊依赖微信数据库。未解析多为 DexKit 预热未完成或当前微信版本未适配。"
    label.contains("Hook 安装任务") ->
        "这些是靠 DexKit 解析后安装的 Hook，未装成的会让对应功能暂时失效。可在「一键修复」里重排重试。"
    label.contains("DexKit 解析") ->
        "显示「未初始化」说明引导链路还没跑完；点「一键修复」重建。"
    label.contains("插件加载错误") ->
        "这里显示各插件最近一次加载失败的原因，反馈给开发者可快速定位。"
    label.contains("市场服务器") ->
        "在线插件市场服务器地址；列表拉不出来时切换官方/备用。"
    label.contains("脚本目录") || label.contains("日志目录") ->
        "路径存在且可读即可；不可写会导致导入/保存插件失败。"
    else -> "该行展示脚本运行时的实时状态。"
}

/** 采集各插件最近一次加载失败原因（读插件目录 log.txt 尾部）。 */
private fun collectPluginLoadErrors(context: Context): List<String> {
    val plugins = runCatching { ScriptPluginRuntime.listPlugins(context) }.getOrDefault(emptyList())
    return runCatching {
        plugins.mapNotNull { plugin ->
            val message = runCatching {
                val logFile = java.io.File(plugin.dir, "log.txt")
                if (!logFile.isFile) {
                    null
                } else {
                    val lines = logFile.readLines().takeLast(120)
                    val index = lines.indexOfLast { it.contains("ERROR") }
                    // log.txt 是追加写的：只有当最后一次「加载失败」晚于最后一次「加载成功」时才算当前错误。
                    val okIndex = lines.indexOfLast { it.contains("插件加载成功") }
                    if (index < 0 || index < okIndex) {
                        null
                    } else {
                        val head = lines[index].substringAfter("ERROR", lines[index]).trim()
                        val detail = lines.getOrNull(index + 1)?.trim().orEmpty()
                        "$head $detail".trim()
                    }
                }
            }.getOrNull()
            message?.takeIf { it.isNotBlank() }?.let { "${plugin.displayName ?: plugin.id}: $it" }
        }
    }.getOrDefault(emptyList())
}

/** 采集脚本运行时诊断信息（label -> value）。 */
private fun collectScriptDiagnostics(context: Context): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()

    // —— 总开关与插件 ——
    val enabled = runCatching {
        HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME)
            .getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE)
    }.getOrDefault(false)
    rows += "插件总开关" to if (enabled) "已开启" else "已关闭"

    val plugins = runCatching { ScriptPluginRuntime.listPlugins(context) }.getOrDefault(emptyList())
    val enabledPlugins = plugins.count {
        runCatching { ScriptPluginRuntime.isPluginEnabled(context, it.id) }.getOrDefault(false)
    }
    rows += "已安装插件" to "${plugins.size} 个（已启用 $enabledPlugins 个）"

    // —— 微信公共 API（脚本运行的前提）——
    val database = runCatching { WeChatApis.database() }.getOrNull()
    val account = runCatching { WeChatApis.account() }.getOrNull()
    val contacts = runCatching { WeChatApis.contacts() }.getOrNull()
    rows += "微信数据库 API" to if (database != null) "已解析" else "✗ 未解析（DexKit 未完成或未适配）"
    rows += "微信账号 API" to if (account != null) "已解析" else "✗ 未解析（DexKit 未完成或未适配）"
    rows += "微信联系人 API" to if (contacts != null) "已解析" else "✗ 未解析（DexKit 未完成或未适配）"

    // —— 引导链路 ——
    val bootstrapStatus = runCatching { HchatScriptBootstrap.status }.getOrDefault("未知")
    rows += "引导链路" to bootstrapStatus
    // 与「插件总开关」使用完全相同的就绪判定，避免诊断页与开关结论不一致。
    val realReadiness = runCatching { ScriptPluginRuntime.scriptApiReadinessDetail() }.getOrNull()
    rows += "未就绪原因" to (realReadiness ?: "无（已就绪）")

    // —— 数据库对象深入状态（开关真正的卡点）——
    val dbApi = database as? WeChatDatabaseApi
    rows += "数据库API可用" to runCatching {
        if (dbApi?.isAvailable() == true) "是（已定位 CoreStorage/SqliteDB 类）"
        else "✗ 否（未解析出 CoreStorage 或 SqliteDB 类）"
    }.getOrDefault("未知")
    rows += "数据库对象就绪" to runCatching {
        if (dbApi?.isReady() == true) "是（已取得 SqliteDB 对象）"
        else "✗ 否（CoreStorage 里找不到 SqliteDB 对象）"
    }.getOrDefault("未知")
    rows += "数据库取库自检" to runCatching { dbApi?.diagnose() ?: "无（数据库 API 未解析）" }
        .getOrDefault("未知")

    // —— Hook 安装任务（脚本依赖的 DexKit 挂钩逐项状态）——
    rows += "就绪阶段" to runCatching { DexInstallScheduler.readyLevelName() }.getOrDefault("未知")
    val tasks = runCatching { DexInstallScheduler.snapshot() }.getOrDefault(emptyList())
    val pendingTasks = tasks.count { !it.installed }
    val pausedTasks = tasks.count { it.paused }
    rows += "Hook 安装任务" to
        ("共 ${tasks.size} 个，已装 ${tasks.size - pendingTasks}，待装 $pendingTasks，失败暂停 $pausedTasks")
    // 逐个列出未安装的任务（最多 10 条，避免刷屏），用户/开发者一眼看到卡在哪一项
    tasks.filter { !it.installed }.take(10).forEach { task ->
        rows += ("      · " + task.label) to task.summary
    }

    // —— 消息发送链路（复读 / 发送文本依赖的构造器自检）——
    rows += "发送文本 API" to runCatching { WeMessageApi.sendTextDiagnostics() }.getOrDefault("未知")

    // —— 各插件最近一次加载失败原因 ——
    val pluginLoadErrors = collectPluginLoadErrors(context)
    rows += "插件加载错误" to if (pluginLoadErrors.isEmpty()) "无" else pluginLoadErrors.joinToString(" ｜ ")
    rows += "DexKit 解析" to runCatching { HchatScriptBootstrap.dexParseInfo() }.getOrDefault("未知")
    rows += "脚本 Hook 注册数" to runCatching { "${HookRegistry.get().size()} 个" }
        .getOrDefault("读取失败")

    // —— 环境 ——
    val wechatVersion = runCatching { WeChatApis.version()?.current()?.displayVersion() }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: "未知"
    rows += "微信版本" to wechatVersion
    rows += "宿主进程" to runCatching { TargetProcesses.currentName }.getOrDefault("未知")
    rows += "模块版本" to "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"

    // —— 存储与服务器 ——
    rows += "脚本目录" to runCatching { ScriptPluginRuntime.scriptDir(context).absolutePath }
        .getOrDefault("未知")
    rows += "日志目录" to runCatching { KnownPaths.moduleData.resolve("logs").toString() }
        .getOrDefault("未知")
    return rows
}



// ---------------------------------------------------------------------------
// 脚本常见问题（FAQ）
// ---------------------------------------------------------------------------

private data class ScriptFaqEntry(val question: String, val answer: String)

private val SCRIPT_FAQ_SECTIONS: List<Pair<String, List<ScriptFaqEntry>>> = listOf(
    "一、插件总开关打不开" to listOf(
        ScriptFaqEntry(
            "点了没反应，或提示「微信联系人数据库尚未就绪」",
            "先打开「脚本功能诊断」看「未就绪原因」这一行，再对症处理：\n" +
                "· 显示「无（已就绪）」却仍开不了 → 重启微信后再试。\n" +
                "· 「数据库对象就绪」为 ✗ 否，或「DexKit 解析：未初始化」→ 微信刚启动、DexKit 还没解析完，等几秒或重启微信。\n" +
                "· 「微信数据库 API：未解析」→ 多为当前微信版本尚未适配，请把诊断页的「微信版本」一行反馈给开发者。"
        ),
        ScriptFaqEntry(
            "怎么确认已经就绪了？",
            "看「插件总开关：已开启」+「未就绪原因：无（已就绪）」两行同时成立即可。"
        )
    ),
    "二、插件开启后马上被关掉" to listOf(
        ScriptFaqEntry(
            "提示「加载[XX]失败，已自动关闭」",
            "这条 Toast 现在会带出真实原因（异常类型 + 说明）；「脚本功能诊断」里的「插件加载错误」一行也会显示最近一次失败的原因。\n" +
                "常见情况：\n" +
                "· 脚本本身与当前微信版本不兼容 / 脚本内部报错 → 把该行内容反馈给开发者。\n" +
                "· 插件文件不完整或被人为改坏 → 删除后从「在线插件」重新安装一次。"
        )
    ),
    "三、插件开了但没反应" to listOf(
        ScriptFaqEntry(
            "开关是开的，但功能不生效",
            "按顺序确认：\n" +
                "1. 「插件总开关」已开启（总开关关着时单个插件不会加载）。\n" +
                "2. 该插件自己的开关已开启（「本地插件」列表里是打开状态）。\n" +
                "3. 看「脚本功能诊断」的「脚本 Hook 注册数」，正常应大于 0。\n" +
                "部分插件只在特定场景生效（例如收到消息、进入群聊），也可能需要重启微信后生效。"
        )
    ),
    "四、改了脚本 / 新装了插件，要不要重启？" to listOf(
        ScriptFaqEntry(
            "脚本是实时加载、实时刷新的",
            "不需要重启：\n" +
                "· 修改插件目录里的 main.java → 约 0.5 秒后自动重新加载该插件。\n" +
                "· 新增 / 删除插件目录、修改 info.prop → 「本地插件」列表会自动刷新。\n" +
                "前提是该插件已被启用、且总开关为开启状态。"
        )
    ),
    "五、在线插件市场打不开" to listOf(
        ScriptFaqEntry(
            "列表加载不出来 / 一直转圈",
            "到「脚本功能诊断」页的「市场服务器」处切换服务器（官方 / 备用 / 自定义）。\n" +
                "某个服务器暂时不可用时，切换后重试即可；自定义地址可填你自己部署的服务端。"
        )
    ),
    "六、日志在哪里" to listOf(
        ScriptFaqEntry(
            "想自己看日志或反馈给开发者",
            "· 脚本目录：/storage/emulated/0/Android/media/com.tencent.mm/WCX/脚本插件\n" +
                "· 单个插件日志：上面目录下「插件文件夹 / log.txt」（含插件自身输出与加载失败原因）\n" +
                "· 模块日志目录：/storage/emulated/0/Android/data/com.tencent.mm/WCX/logs\n" +
                "· 也可以在「脚本功能诊断」页点「复制诊断信息」，一次性复制当前状态。"
        )
    ),
    "七、插件总开关自己关了 / 每次都要重开" to listOf(
        ScriptFaqEntry(
            "明明开过，下次进来又变成关闭",
            "总开关本身不会自动关闭，出现这种情况通常是：\n" +
                "· 微信被强杀 / 清理数据后，配置还没落盘 → 重新打开一次即可。\n" +
                "· 你操作的设置页与脚本运行不在同一进程 → 到「脚本功能诊断」确认「插件总开关」的真实值。\n" +
                "· 仍反复关闭：点「一键修复」会自动开启；若依旧，把「复制诊断信息」的内容反馈给开发者。"
        )
    ),
    "八、开关都正常，但脚本就是不生效" to listOf(
        ScriptFaqEntry(
            "总开关已开、诊断全绿，功能却没反应",
            "按这个顺序自查：\n" +
                "1. 该插件自己的开关是否已打开（总开关只决定「能不能加载」，单个插件还要各自启用）。\n" +
                "2. 插件是否只在特定场景触发（收到消息 / 进入聊天 / 打开朋友圈等），需要在对应界面操作才看得到效果。\n" +
                "3. 看诊断页「脚本 Hook 注册数」，正常应大于 0；若为 0，点「一键修复」重跑引导链路。\n" +
                "4. 重启微信后再试一次；仍无效，把「微信版本」+ 插件名反馈给开发者判断兼容性。"
        )
    ),
    "九、提示「Class: PluginCallBack.HttpCallback not found in namespace」" to listOf(
        ScriptFaqEntry(
            "插件加载失败，报某个类找不到",
            "这是「脚本运行库（ABI）」的关键类缺失，属于模块打包问题，不是脚本本身写错。\n" +
                "· 先确认已更新到最新版本；到「脚本功能诊断」看「脚本运行库（ABI）」一行。\n" +
                "· 若该项为 ✗，说明当前版本缺少关键类，需要更新模块，一键修复无法解决。"
        )
    ),
    "十、如何彻底重装脚本子系统" to listOf(
        ScriptFaqEntry(
            "试了很多次还是不行，想从头来一遍",
            "1. 「脚本功能诊断」→「一键修复」，让脚本子链路重新初始化。\n" +
                "2. 仍在不行：把微信从最近任务里划掉，确保进程结束后重新打开。\n" +
                "3. 升级模块后若脚本突然全部失效，请「先卸载旧版、再安装新版」——不同签名的安装包无法直接覆盖安装。\n" +
                "4. 以上都无效，请「复制诊断信息」反馈，通常能直接定位到卡在哪一步。"
        )
    )
)

/**
 * 脚本常见问题页。
 *
 * 把「开关开不了 / 插件启用失败 / 改动后要不要重启」等高频问题的处理方式集中一处，
 * 出问题时先看这里，再决定是否需要到「脚本功能诊断」取更多信息。
 */
@Composable
fun ScriptFaqScreen(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = "脚本常见问题",
        largeTitle = "脚本常见问题",
        scrollBehavior = scrollBehavior,
        onBack = onBack,
    ) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp,
            ),
        ) {
            SCRIPT_FAQ_SECTIONS.forEach { (sectionTitle, entries) ->
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = sectionTitle) }
                item {
                    SettingsCard {
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) InsetDivider()
                            FaqItem(entry.question, entry.answer)
                        }
                    }
                }
            }
            item {
                Text(
                    text = "以上仍未解决时，请到「脚本功能诊断」页点「复制诊断信息」，把内容反馈给开发者。",
                    fontSize = 12.sp,
                    color = BasicComponentDefaults.summaryColor().color,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun FaqItem(question: String, answer: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = question,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = BasicComponentDefaults.titleColor().color,
        )
        Text(
            text = answer,
            fontSize = 12.sp,
            color = BasicComponentDefaults.summaryColor().color,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------
//  DEX 适配风格总览（脚本功能诊断页）
// ---------------------------------------------------------------------------
@Composable
private fun DexScriptOverview(
    moduleVersion: String,
    wechatVersion: String,
    rowsCount: Int,
    lastRefresh: String,
    onRescan: () -> Unit
) {
    val ready = runCatching {
        val d = ScriptPluginRuntime.scriptApiReadinessDetail()
        d.isNullOrBlank()
    }.getOrDefault(false)
    val hookPending = runCatching { h.Hchat.hooks.core.DexInstallScheduler.pendingCount() }.getOrNull() ?: 0
    val badCount = if (ready && hookPending == 0) 0 else 1
    val primary = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    val onSurfaceVar = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val surface = MiuixTheme.colorScheme.surface
    val surfaceVar = MiuixTheme.colorScheme.secondaryVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(surface)
            .border(1.dp, surfaceVar, RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DEX 适配", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = onSurface)
                Spacer(Modifier.weight(1f))
                Text(
                    if (badCount == 0) "通过" else "需修复",
                    fontSize = 13.sp, color = primary, fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (badCount == 0) "脚本适配检查完成" else "脚本运行时尚未完全就绪",
                fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = if (badCount == 0) onSurface else primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "已检查 " + rowsCount + " 项，待装 Hook " + hookPending,
                fontSize = 14.sp, color = onSurfaceVar
            )
            Spacer(Modifier.height(16.dp))
            Text("模块版本：" + moduleVersion, fontSize = 13.sp, color = onSurfaceVar)
            Text("当前微信：" + wechatVersion, fontSize = 13.sp, color = onSurfaceVar)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(primary.copy(alpha = 0.25f)))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DexStatCell("已检查", rowsCount.toString(), Modifier.weight(1f))
                DexStatCell("Hook", hookPending.toString(), Modifier.weight(1f))
                DexStatCell("就绪", if (ready) "是" else "否", Modifier.weight(1f))
                DexStatCell("耗时", "—", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "检查通过表示底层接口可用，不等于所有使用场景已验证。",
                fontSize = 12.sp, color = onSurfaceVar,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(primary)
                    .clickable { onRescan() },
                contentAlignment = Alignment.Center
            ) {
                Text("刷新诊断信息", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "最后刷新：" + lastRefresh,
                fontSize = 11.sp, color = onSurfaceVar,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End
            )
        }
    }
}


/** DEX 适配风 4 格统计网格单格（脚本诊断页）。 */
@Composable
private fun DexStatCell(label: String, value: String, modifier: Modifier = Modifier) {
    val onSurface = MiuixTheme.colorScheme.onSurface
    val onSurfaceVar = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.secondaryVariant.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = onSurface)
            Spacer(Modifier.height(2.dp))
            Text(text = label, fontSize = 12.sp, color = onSurfaceVar)
        }
    }
}
