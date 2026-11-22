package com.Johnny.wcx.features.items.chat

import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Forward
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.ForwardIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.AudioUtils
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 语音多会话批量转发。
 *
 * 两条入口：
 * 1. 聊天里多选消息 → 底部操作栏 WCX 菜单 → 「语音批量转发」（复用既有
 *    [WeChatMessageContextMenuApi] 的注入方式，长按单条语音也走这里）。
 * 2. 兜底入口：功能设置页 → 「手动选择会话批量转发」→ 选源会话（按最近 N 条扫 message 表的语音）
 *    → 选目标会话 → 进同一个队列。多选菜单的注入依赖模块既有多选锚点，万一某版失效，
 *    这条入口不依赖任何新锚点，功能仍然可用。
 *
 * 链路全部复用既有实现，不自己造发送：
 * - 语音文件路径：`MessageInfo.imagePath`（= message 表 imgPath）→ [WeMessageApi.getVoiceFullPath]
 * - 时长：[AudioUtils.getDurationMs]；native 解析不出来时退回语音 XML 的 length 属性
 * - 发送：[WeMessageApi.sendVoice]（8.0.80 唯一可用的语音发送出口）
 *
 * 队列：`CoroutineScope(SupervisorJob() + Dispatchers.IO)` + 顺序 for 循环，
 * 每条之间 delay(间隔)；间隔 / 重试次数 / 目标上限 / 总超时都走 WePrefs。
 */
@Feature(
    name = "语音批量转发",
    categories = ["聊天"],
    description = "多选语音消息后转发到多个会话: 队列逐条发送、每条间隔可配置、失败自动重试一次并汇总失败项"
)
object VoiceBatchForward : ClickableFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "VoiceBatchForward"

    /** 菜单项 id：避开既有功能已占用的 777000(合并入口) / 777003(存本地) / 777010(转发)。 */
    private const val MENU_ITEM_ID = 777031

    private const val KEY_INTERVAL_MS = "voice_batch_forward_interval_ms"
    private const val KEY_RETRY_TIMES = "voice_batch_forward_retry_times"
    private const val KEY_MAX_TARGETS = "voice_batch_forward_max_targets"
    private const val KEY_TIMEOUT_MINUTES = "voice_batch_forward_timeout_minutes"
    private const val KEY_SOURCE_LIMIT = "voice_batch_forward_source_limit"

    private const val DEFAULT_INTERVAL_MS = 1_000
    private const val MIN_INTERVAL_MS = 300
    private const val MAX_INTERVAL_MS = 10_000

    private const val DEFAULT_RETRY_TIMES = 1
    private const val MAX_RETRY_TIMES = 2

    private const val DEFAULT_MAX_TARGETS = 20
    private const val MAX_MAX_TARGETS = 50

    private const val DEFAULT_TIMEOUT_MINUTES = 10
    private const val MAX_TIMEOUT_MINUTES = 60

    private const val DEFAULT_SOURCE_LIMIT = 20
    private const val MAX_SOURCE_LIMIT = 50

    /** 失败明细最多展示多少条（避免弹窗被刷爆）。 */
    private const val MAX_FAILURE_SAMPLES = 10

    /** 失败重试前的额外等待，比正常间隔再保守一点。 */
    private const val RETRY_BACKOFF_MS = 1_500L

    /** 弹窗之间留一点间隔，避免上一个弹窗还没收起就弹下一个。 */
    private const val DIALOG_GAP_MS = 180L

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var runningJob: Job? = null

    /** 语音 XML 里的时长字段（毫秒）。 */
    private val voiceLengthRegex = Regex("(?:length|voicelength)=\"(\\d+)\"")

    override fun onEnable() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        runCatching { WeChatMessageContextMenuApi.removeProvider(this) }
            .onFailure { WeLogger.e(TAG, "移除菜单 provider 失败", it) }
        runCatching { runningJob?.cancel() }
        runCatching { scope.cancel() }
        runningJob = null
    }

    // ---------------------------------------------------------------- 菜单入口

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                MENU_ITEM_ID,
                "语音批量转发",
                ForwardIcon,
                MaterialSymbols.Outlined.Forward,
                // 单条长按：只有语音消息才显示
                isSupported = { msgInfo -> msgInfo.typeCode == MessageType.VOICE.code },
                // 多选：选中的消息里至少有一条语音就允许进队列（非语音项会被忽略）
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Adapted(
                    isSupported = { msgs ->
                        msgs.isNotEmpty() && msgs.any { it.typeCode == MessageType.VOICE.code }
                    },
                    onClick = { view, _, msgs -> startPicking(view, msgs) }
                )
            ) { view, _, msgInfo ->
                startPicking(view, listOf(msgInfo))
            }
        )
    }

    // ---------------------------------------------------------------- 入口 1：多选 / 长按

    private fun startPicking(view: View, selected: List<MessageInfo>) {
        val voices = selected
            .filter { it.typeCode == MessageType.VOICE.code }
            .map { VoiceItem(encPath = it.imagePath, content = it.content) }
        val skipped = selected.size - voices.size
        if (voices.isEmpty()) {
            showToast("选中的消息里没有语音，无法批量转发")
            return
        }
        if (skipped > 0) {
            WeLogger.i(TAG, "批量转发忽略 $skipped 条非语音消息")
        }
        pickTargets(view.context, voices)
    }

    // ---------------------------------------------------------------- 入口 2：设置页手动批量

    private fun startManualBatch(context: Context) {
        scope.launch {
            val contacts = loadContacts()
            if (contacts.isEmpty()) {
                showToastSuspend("没有可选的联系人或群聊（通讯录读取失败或为空）")
                return@launch
            }
            if (!isHostAlive(context)) return@launch

            withContext(Dispatchers.Main) {
                showComposeDialog(context) {
                    ContactsSelector(
                        title = "第 1 步：选择源会话（从哪个聊天里取语音）",
                        contacts = contacts,
                        initialSelectedWxIds = emptySet(),
                        fullScreen = true,
                        onDismiss = onDismiss,
                        onConfirm = { selectedWxIds ->
                            val source = selectedWxIds.firstOrNull()
                            if (source.isNullOrBlank()) {
                                showToast("请选择源会话")
                                return@ContactsSelector
                            }
                            onDismiss()
                            loadSourceVoices(context, contacts, source)
                        }
                    )
                }
            }
        }
    }

    /** 扫 message 表取源会话最近的语音消息，然后走第二段选目标会话。 */
    private fun loadSourceVoices(context: Context, contacts: List<IWeContact>, talker: String) {
        val limit = WePrefs.getIntOrDef(KEY_SOURCE_LIMIT, DEFAULT_SOURCE_LIMIT)
            .coerceIn(1, MAX_SOURCE_LIMIT)
        scope.launch {
            val voices = loadRecentVoices(talker, limit)
            if (voices.isEmpty()) {
                showToastSuspend("这个会话最近 $limit 条消息里没有语音")
                return@launch
            }
            showToastSuspend("已从源会话取到 ${voices.size} 条语音，请选择目标会话")
            delay(DIALOG_GAP_MS)
            withContext(Dispatchers.Main) {
                showComposeDialog(context) {
                    ContactsSelector(
                        title = "第 2 步：选择目标会话（${voices.size} 条语音）",
                        contacts = contacts,
                        initialSelectedWxIds = emptySet(),
                        fullScreen = true,
                        onDismiss = onDismiss,
                        onConfirm = { selectedWxIds ->
                            if (selectedWxIds.isEmpty()) {
                                showToast("请选择至少一个目标会话")
                                return@ContactsSelector
                            }
                            onDismiss()
                            dispatchQueue(context, voices, selectedWxIds.toList(), contacts)
                        }
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- 选目标会话

    private fun pickTargets(context: Context, voices: List<VoiceItem>) {
        scope.launch {
            val contacts = loadContacts()
            if (contacts.isEmpty()) {
                showToastSuspend("没有可选的联系人或群聊（通讯录读取失败或为空）")
                return@launch
            }
            if (!isHostAlive(context)) return@launch

            withContext(Dispatchers.Main) {
                showComposeDialog(context) {
                    ContactsSelector(
                        title = "选择转发目标（${voices.size} 条语音）",
                        contacts = contacts,
                        initialSelectedWxIds = emptySet(),
                        // 目标多的时候小窗只能看到几个人，直接给整屏
                        fullScreen = true,
                        onDismiss = onDismiss,
                        onConfirm = { selectedWxIds ->
                            if (selectedWxIds.isEmpty()) {
                                showToast("请选择至少一个目标会话")
                                return@ContactsSelector
                            }
                            onDismiss()
                            dispatchQueue(context, voices, selectedWxIds.toList(), contacts)
                        }
                    )
                }
            }
        }
    }

    private fun loadContacts(): List<IWeContact> = runCatching {
        WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
    }.onFailure {
        WeLogger.e(TAG, "读取联系人/群聊失败", it)
    }.getOrDefault(emptyList())

    private fun isHostAlive(context: Context): Boolean {
        val alive = runCatching { context is ComponentActivity && !(context as ComponentActivity).isFinishing }
            .getOrDefault(true)
        if (!alive) WeLogger.w(TAG, "宿主界面已结束，放弃弹出选择框")
        return alive
    }

    /** 按目标上限裁剪后交给队列；同时把 wxid → 显示名映射传下去，进度里看得懂。 */
    private fun dispatchQueue(
        context: Context,
        voices: List<VoiceItem>,
        targets: List<String>,
        contacts: List<IWeContact>,
    ) {
        val limit = WePrefs.getIntOrDef(KEY_MAX_TARGETS, DEFAULT_MAX_TARGETS).coerceIn(1, MAX_MAX_TARGETS)
        val capped = targets.take(limit)
        if (capped.size < targets.size) {
            showToast("本次最多转发 $limit 个会话，已忽略多余的 ${targets.size - limit} 个")
        }
        val names = HashMap<String, String>(contacts.size)
        contacts.forEach { contact -> names[contact.wxId] = contact.displayName }
        startQueue(context, voices, capped, names)
    }

    /** 从 message 表取某会话最近的语音消息（每条只留 imgPath + content，够算路径和时长）。 */
    private fun loadRecentVoices(talker: String, limit: Int): List<VoiceItem> {
        return runCatching {
            WeDatabaseApi.rawQuery(
                "SELECT imgPath, content FROM message WHERE talker=? AND type=34" +
                    " ORDER BY createTime DESC LIMIT $limit",
                arrayOf<Any>(talker)
            ).use { cursor ->
                val list = ArrayList<VoiceItem>(limit)
                while (cursor.moveToNext()) {
                    list.add(VoiceItem(cursor.getString(0), cursor.getString(1) ?: ""))
                }
                list
            }
        }.onFailure {
            WeLogger.e(TAG, "扫描源会话语音失败：talker=$talker", it)
        }.getOrDefault(emptyList()).asReversed()
    }

    // ---------------------------------------------------------------- 队列

    private fun startQueue(
        context: Context,
        voices: List<VoiceItem>,
        targets: List<String>,
        displayNames: Map<String, String>,
    ) {
        val total = voices.size * targets.size
        if (total <= 0) return

        val previous = runningJob
        if (previous != null && previous.isActive) {
            showToast("已有一个批量转发任务在跑，先等它结束（或点取消）")
            return
        }

        val progress = ForwardProgress(total)
        val job = scope.launch {
            try {
                runQueue(progress, voices, targets, displayNames)
            } catch (e: CancellationException) {
                WeLogger.i(TAG, "批量转发协程被取消（已完成 ${progress.done}/${progress.total}）")
                progress.finish("已取消（已完成 ${progress.done}/${progress.total}）")
                throw e
            } catch (e: Throwable) {
                WeLogger.e(TAG, "批量转发异常终止", e)
                progress.finish("异常终止：" + (e.message ?: e.javaClass.simpleName))
            }
        }
        runningJob = job

        // 进度弹窗：不可点外部关闭，结束（或取消）后手动关
        scope.launch {
            delay(DIALOG_GAP_MS)
            withContext(Dispatchers.Main) {
                runCatching {
                    showComposeDialog(context, directlyDismissable = false) {
                        ForwardProgressContent(progress, onDismiss)
                    }
                }.onFailure {
                    WeLogger.e(TAG, "进度弹窗展示失败", it)
                    showToast("进度窗口展示失败，任务仍在后台继续（可看日志）")
                }
            }
        }
    }

    private suspend fun runQueue(
        progress: ForwardProgress,
        voices: List<VoiceItem>,
        targets: List<String>,
        displayNames: Map<String, String>,
    ) {
        val intervalMs = WePrefs.getIntOrDef(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS).toLong()
        val maxRetry = WePrefs.getIntOrDef(KEY_RETRY_TIMES, DEFAULT_RETRY_TIMES)
            .coerceIn(0, MAX_RETRY_TIMES)
        val timeoutMinutes = WePrefs.getIntOrDef(KEY_TIMEOUT_MINUTES, DEFAULT_TIMEOUT_MINUTES)
            .coerceIn(1, MAX_TIMEOUT_MINUTES)
        val deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L

        WeLogger.i(
            TAG,
            "批量转发开始：${voices.size} 条语音 × ${targets.size} 个目标 = ${progress.total} 次，" +
                "间隔 ${intervalMs}ms，重试 $maxRetry 次，总超时 ${timeoutMinutes} 分钟"
        )

        // 微信刚启动时发送组件可能还没定位好，先等（内部最多 8 秒），比直接甩一堆失败有用
        if (!WeMessageApi.awaitSendReady()) {
            progress.finish("发送组件未就绪（模块还在解析微信），请稍后重试")
            showToastSuspend("模块正在解析微信（发送组件未就绪），本次未发送任何语音")
            return
        }

        var handled = 0
        var stopReason: String? = null

        outer@ for (target in targets) {
            for (voice in voices) {
                if (progress.cancelRequested) {
                    stopReason = "已取消（已完成 $handled/${progress.total}）"
                    break@outer
                }
                if (System.currentTimeMillis() > deadline) {
                    stopReason = "总超时（${timeoutMinutes} 分钟），已停止后续发送"
                    break@outer
                }

                val targetLabel = displayNames[target] ?: target
                handled += 1
                progress.markCurrent(handled, "第 $handled 条 → $targetLabel")

                var reason: String? = null
                var attempt = 0
                while (attempt <= maxRetry) {
                    reason = forwardOne(target, voice)
                    if (reason == null) break
                    attempt += 1
                    if (attempt <= maxRetry) {
                        WeLogger.w(TAG, "发送失败，准备第 $attempt 次重试（目标 $target）：$reason")
                        delay(RETRY_BACKOFF_MS)
                    }
                }

                if (reason == null) {
                    progress.markSuccess(handled)
                } else {
                    WeLogger.w(TAG, "语音转发最终失败：目标 $target，原因 $reason")
                    progress.markFailure(handled, targetLabel, reason)
                }

                // 限速：最后一条不用等
                if (handled < progress.total && System.currentTimeMillis() + intervalMs < deadline) {
                    delay(intervalMs)
                }
            }
        }

        progress.finish(stopReason)

        val summary = if (progress.failed == 0) {
            "语音批量转发完成：${progress.succeeded}/${progress.total} 条成功"
        } else {
            "语音批量转发结束：成功 ${progress.succeeded} 条，失败 ${progress.failed} 条（共 ${progress.total} 条）"
        }
        WeLogger.i(TAG, summary + (stopReason?.let { "；$it" } ?: ""))
        showToastSuspend(summary)
    }

    /** 转发一条语音；成功返回 null，失败返回可直接展示的中文原因。 */
    private fun forwardOne(toUser: String, item: VoiceItem): String? {
        return runCatching {
            val encPath = item.encPath
            if (encPath.isNullOrBlank()) {
                "语音未下载（消息里没有文件引用）"
            } else {
                val voicePath = WeMessageApi.getVoiceFullPath(encPath)
                if (voicePath.isBlank()) {
                    "语音路径解析失败（imgPath=$encPath）"
                } else {
                    val file = java.io.File(voicePath)
                    if (!file.isFile || file.length() <= 0L) {
                        "语音文件不存在（可能已被微信清理）：$voicePath"
                    } else {
                        val durationMs = resolveDurationMs(voicePath, item.content)
                        if (WeMessageApi.sendVoice(toUser, voicePath, durationMs)) null
                        else "微信拒绝发送（时长 ${durationMs}ms）"
                    }
                }
            }
        }.getOrElse {
            WeLogger.e(TAG, "转发语音异常：target=$toUser", it)
            "异常：" + (it.message ?: it.javaClass.simpleName)
        }
    }

    /**
     * 时长换算：优先用 native 解析（和既有「转发消息」「复读」一致），
     * native 拿不到（返回 0）时退回语音 XML 里的 length 字段，避免发出去是 0 秒气泡。
     */
    private fun resolveDurationMs(voicePath: String, content: String): Int {
        val fromNative = runCatching { AudioUtils.getDurationMs(voicePath) }.getOrDefault(0L)
        if (fromNative > 0L) return fromNative.coerceIn(1L, 60_000L).toInt()

        val fromXml = runCatching {
            voiceLengthRegex.find(content)?.groupValues?.getOrNull(1)?.toLongOrNull()
        }.getOrNull()
        if (fromXml == null) {
            WeLogger.w(TAG, "语音时长解析失败（native 与 XML 都没有），按 1 秒发送：$voicePath")
        }
        return (fromXml ?: 1_000L).coerceIn(1L, 60_000L).toInt()
    }

    // ---------------------------------------------------------------- 数据结构 / 进度

    /** 队列里的一条语音：只留定位文件与算时长需要的最小字段。 */
    private class VoiceItem(val encPath: String?, val content: String)

    private class ForwardProgress(val total: Int) {
        var done by mutableStateOf(0)
        var succeeded by mutableStateOf(0)
        var failed by mutableStateOf(0)
        var current by mutableStateOf("")
        var finished by mutableStateOf(false)
        var summary by mutableStateOf<String?>(null)
        var failureSamples by mutableStateOf<List<String>>(emptyList())

        @Volatile
        var cancelRequested: Boolean = false

        fun markCurrent(index: Int, label: String) {
            done = (index - 1).coerceAtLeast(0)
            current = label
        }

        fun markSuccess(index: Int) {
            done = index
            succeeded += 1
        }

        fun markFailure(index: Int, target: String, reason: String) {
            done = index
            failed += 1
            if (failureSamples.size < 10) {
                failureSamples = failureSamples + "$target：$reason"
            }
        }

        fun finish(reason: String?) {
            done = done.coerceAtMost(total)
            current = ""
            summary = reason
            finished = true
        }
    }

    @Composable
    private fun ForwardProgressContent(progress: ForwardProgress, onDismiss: () -> Unit) {
        AlertDialogContent(
            title = {
                Text(if (progress.finished) "批量转发语音 · 已结束" else "批量转发语音 · 进行中")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "已完成 ${progress.done}/${progress.total} 条" +
                            "（成功 ${progress.succeeded}，失败 ${progress.failed}）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (progress.total <= 0) 0f
                            else (progress.done.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!progress.finished && progress.current.isNotBlank()) {
                        Text(progress.current, style = MaterialTheme.typography.bodySmall)
                    }
                    progress.summary?.takeIf { it.isNotBlank() }?.let { text ->
                        Text(text, style = MaterialTheme.typography.bodySmall)
                    }
                    if (progress.failureSamples.isNotEmpty()) {
                        Text("失败明细：", style = MaterialTheme.typography.bodySmall)
                        progress.failureSamples.forEach { sample ->
                            Text("· $sample", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!progress.finished) {
                        Text(
                            "提示：取消只停止后续发送，已发出的语音不会撤回。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (progress.finished) {
                        onDismiss()
                    } else {
                        progress.cancelRequested = true
                    }
                }) {
                    Text(if (progress.finished) "关闭" else "取消")
                }
            }
        )
    }

    // ---------------------------------------------------------------- 设置入口

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var intervalInput by remember {
                mutableStateOf(WePrefs.getIntOrDef(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS).toString())
            }
            var retryInput by remember {
                mutableStateOf(WePrefs.getIntOrDef(KEY_RETRY_TIMES, DEFAULT_RETRY_TIMES).toString())
            }
            var maxTargetsInput by remember {
                mutableStateOf(WePrefs.getIntOrDef(KEY_MAX_TARGETS, DEFAULT_MAX_TARGETS).toString())
            }
            var sourceLimitInput by remember {
                mutableStateOf(WePrefs.getIntOrDef(KEY_SOURCE_LIMIT, DEFAULT_SOURCE_LIMIT).toString())
            }

            AlertDialogContent(
                title = { Text("语音批量转发") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Text(
                            "用法一：聊天里多选若干条语音 → 底部 WCX 菜单 → 语音批量转发 → 勾选目标会话。\n" +
                                "用法二：下面的按钮，手动选源会话与目标会话（多选菜单失效时用这条）。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextField(
                            value = intervalInput,
                            onValueChange = { intervalInput = it.filter(Char::isDigit).take(5) },
                            label = { Text("每条间隔 (毫秒，${MIN_INTERVAL_MS}-${MAX_INTERVAL_MS})") }
                        )
                        TextField(
                            value = retryInput,
                            onValueChange = { retryInput = it.filter(Char::isDigit).take(1) },
                            label = { Text("失败重试次数 (0-${MAX_RETRY_TIMES})") }
                        )
                        TextField(
                            value = maxTargetsInput,
                            onValueChange = { maxTargetsInput = it.filter(Char::isDigit).take(2) },
                            label = { Text("单次最多目标会话数 (1-${MAX_MAX_TARGETS})") }
                        )
                        TextField(
                            value = sourceLimitInput,
                            onValueChange = { sourceLimitInput = it.filter(Char::isDigit).take(2) },
                            label = { Text("源会话最多取多少条语音 (1-${MAX_SOURCE_LIMIT})") }
                        )
                        Button(onClick = {
                            val limit = sourceLimitInput.toIntOrNull()?.coerceIn(1, MAX_SOURCE_LIMIT)
                                ?: DEFAULT_SOURCE_LIMIT
                            WePrefs.putInt(KEY_SOURCE_LIMIT, limit)
                            onDismiss()
                            startManualBatch(context)
                        }) { Text("手动选择会话批量转发") }
                        Text(
                            "间隔太短容易触发微信服务端风控（表现为后面一批发不出去），建议不低于 1000ms。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        val interval = intervalInput.toIntOrNull()
                        val retry = retryInput.toIntOrNull()
                        val maxTargets = maxTargetsInput.toIntOrNull()
                        val sourceLimit = sourceLimitInput.toIntOrNull()
                        if (interval == null || interval < MIN_INTERVAL_MS || interval > MAX_INTERVAL_MS) {
                            showToast("间隔范围: ${MIN_INTERVAL_MS}-${MAX_INTERVAL_MS} 毫秒")
                            return@Button
                        }
                        if (retry == null || retry < 0 || retry > MAX_RETRY_TIMES) {
                            showToast("重试次数范围: 0-${MAX_RETRY_TIMES}")
                            return@Button
                        }
                        if (maxTargets == null || maxTargets < 1 || maxTargets > MAX_MAX_TARGETS) {
                            showToast("目标会话数范围: 1-${MAX_MAX_TARGETS}")
                            return@Button
                        }
                        if (sourceLimit == null || sourceLimit < 1 || sourceLimit > MAX_SOURCE_LIMIT) {
                            showToast("源会话语音条数范围: 1-${MAX_SOURCE_LIMIT}")
                            return@Button
                        }
                        WePrefs.putInt(KEY_INTERVAL_MS, interval)
                        WePrefs.putInt(KEY_RETRY_TIMES, retry)
                        WePrefs.putInt(KEY_MAX_TARGETS, maxTargets)
                        WePrefs.putInt(KEY_SOURCE_LIMIT, sourceLimit)
                        onDismiss()
                        showToast("已保存")
                    }) { Text("保存") }
                }
            )
        }
    }
}
