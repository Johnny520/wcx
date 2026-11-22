package com.Johnny.wcx.activity.settings.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.ai.AiAuditPlan
import com.Johnny.wcx.ai.AiDates
import com.Johnny.wcx.ai.AiMemoryIndex
import com.Johnny.wcx.ai.AiProfile
import com.Johnny.wcx.ai.AiProfileAudit
import com.Johnny.wcx.ai.AiProfileInitializer
import com.Johnny.wcx.ai.AiProfileLimits
import com.Johnny.wcx.ai.AiProfileState
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.ai.AiPrompts
import com.Johnny.wcx.ai.AiTokens
import com.Johnny.wcx.ai.AiTools
import com.Johnny.wcx.ai.ChatGoal
import com.Johnny.wcx.ai.MemoryEntry
import com.Johnny.wcx.ai.ProfileChange
import com.Johnny.wcx.ai.ProfileTag
import com.Johnny.wcx.ai.ReplyStyle
import com.Johnny.wcx.ui.content.ScrimDialog
import com.Johnny.wcx.ui.style.SoftCard
import com.Johnny.wcx.ui.style.WxPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 好友管理弹窗。
 *
 * 分页共用一个对话框（不用嵌套遮罩 —— 两层遮罩叠在一起会变暗两遍，
 * 返回键也说不清该关哪一层）：
 *
 * ```
 * 0 概览（未初始化 / 整理中 / 审查中 / 已就绪）
 * 1 聊天目标
 * 2 标签管理
 * 3 记忆管理
 * 4 审查建议（模型提案，勾选后执行）
 * 5 变动记录
 * 6 回复风格（用户本人的说话方式，可手改）
 * ```
 */
@Composable
internal fun AiProfileDialog(
    wxId: String,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }
    var profile by remember { mutableStateOf<AiProfile?>(null) }
    /** 首次读取是否完成。完成前 `profile == null` 只代表「还没读到」，不是读取失败。 */
    var loaded by remember { mutableStateOf(false) }
    var initializing by remember { mutableStateOf(false) }
    /** 待确认的动作：`full` / `incremental` / `audit`。点按钮先弹确认，避免误触直接开跑。 */
    var confirm by remember { mutableStateOf<String?>(null) }
    /** 已经自动跳转过的提案时间戳：用户按返回后不再强行弹回去。 */
    var proposalPromptedAt by remember { mutableStateOf(0L) }
    /** 刚执行完的审查建议：概览页当场显示一条结果，关掉弹窗即不再出现（历史在「变动记录」）。 */
    var appliedChange by remember { mutableStateOf<ProfileChange?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        var latest = withContext(Dispatchers.IO) { AiProfileStore.readProfile(wxId) }
        if (latest == null) {
            // 整理/审查写档案是「临时文件 + rename」。个别文件系统在这个窗口里读会瞬时失败，
            // 再试一次，避免把一次抖动渲染成红字「档案读取失败」。
            delay(120)
            latest = withContext(Dispatchers.IO) { AiProfileStore.readProfile(wxId) }
        }
        // 读失败时保留上一次成功的内容，不要用 null 把整页覆盖成错误态
        //（审查期间每 700ms 轮询一次，一次抖动就会闪一下红字）。
        if (latest != null) profile = latest
        loaded = true
    }

    LaunchedEffect(wxId) { reload() }

    /**
     * 「改档案 → 重读 → 通知外层」的唯一收口。
     *
     * 六处保存（聊天目标 / 标签 / 侧写 / 恢复自动归纳 / 两个开关）原本各写一遍
     * `launch { patchProfile; reload; onChanged }`，加一个字段就要记得抄三步，
     * 漏掉 `reload` 会让页面停在旧值。需要额外动作（例如保存后关页面）用 [then]。
     */
    fun save(block: (AiProfile) -> AiProfile, then: () -> Unit = {}) {
        scope.launch {
            withContext(Dispatchers.IO) { AiProfileStore.patchProfile(wxId, block) }
            reload()
            onChanged()
            then()
        }
    }

    // 初始化期间轮询档案：进度写在文件里，页面没有别的通知渠道。
    // 结束条件必须同时看「状态」和「线程」——
    //   * 只看 `initializing` 的话，初始化完成后这个标记没人清，会一直每 700ms 读一次文件；
    //   * 只看 `state` 的话，刚点开始的头几十秒（还在统计全量记录、没写 INITIALIZING）
    //     会被判成「没在跑」，进度页一闪就退回「开始初始化」，看起来像点了没反应。
    LaunchedEffect(profile?.state, initializing, profile?.auditing) {
        val polling = initializing ||
            profile?.state == AiProfileState.INITIALIZING ||
            profile?.auditing == true
        if (!polling) return@LaunchedEffect
        while (true) {
            delay(700)
            reload()
            val current = profile ?: break
            // 线程还在跑就继续等：它可能还卡在分片统计里，档案状态尚未落到 INITIALIZING。
            val running = AiProfileInitializer.isRunning(wxId) || AiProfileAudit.isRunning(wxId)
            if (running) continue
            if (current.state != AiProfileState.INITIALIZING && !current.auditing) {
                initializing = false
                if (current.state == AiProfileState.READY) onChanged()
                break
            }
        }
    }

    BackHandler {
        if (page != 0) page = 0 else onDismiss()
    }

    // 有未处理的提案就摆到面前。只跳一次：用户按返回说明想先看看别的，不能每次回到概览
    // 都被弹回去。不设时限 —— 多个好友连着审查时，用户往往是过一阵才逐个回来处理，
    // 放着不处理就一直留着（提案页上有生成时间，能分辨是哪一次审查的）。
    LaunchedEffect(profile?.pendingAudit?.createdAt, profile?.busy) {
        val plan = profile?.pendingAudit ?: return@LaunchedEffect
        if (plan.isEmpty || profile?.busy == true) return@LaunchedEffect
        if (plan.createdAt == proposalPromptedAt) return@LaunchedEffect
        proposalPromptedAt = plan.createdAt
        if (page == 0) page = 4
    }

    val title = when (page) {
        1 -> "聊天目标"
        2 -> "标签"
        3 -> "记忆"
        4 -> "审查建议"
        5 -> "变动记录"
        6 -> "回复风格"
        else -> null
    }

    // 增量整理的起点文案：最新一条事件的日期，没有记忆时退回「上次整理时间」。
    val incrementalFrom = remember(profile?.memories) {
        profile?.memories?.maxByOrNull { AiDates.sortKey(it.occurredAt) }
            ?.let { AiDates.display(it.occurredAt) }
            ?: "上次整理时间"
    }

    ScrimDialog(show = true, onDismissRequest = onDismiss, title = title) {
        Box(Modifier.heightIn(max = 520.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val current = profile
                when {
                    current == null -> Text(
                        text = if (loaded) "档案读取失败" else "正在读取档案…",
                        fontSize = 14.sp,
                        color = if (loaded) WxPalette.Danger else WxPalette.textSecondary(),
                    )
                    page == 1 -> GoalEditor(
                        profile = current,
                        onSave = { goal ->
                            save({ it.copy(goal = goal) }) { page = 0 }
                        },
                    )
                    page == 2 -> TagEditor(
                        profile = current,
                        onUpdate = { tags ->
                            // 手改也是「现在确认」：值变了就戳时间，否则这条会被审查当成
                            // 「超过一年没有新证据」合并掉。没改值的不动，避免点开保存一次
                            // 就把全部标签刷成今天，让老化信息失效。
                            val now = System.currentTimeMillis() / 1000
                            val old = current.tags.associateBy { it.id }
                            val stamped = tags.map { tag ->
                                val prev = old[tag.id]
                                if (prev != null && prev.value == tag.value && prev.updatedAt > 0) tag
                                else tag.copy(updatedAt = now)
                            }
                            save({ it.copy(tags = stamped) })
                        },
                    )
                    page == 3 -> MemoryList(profile = current)
                    page == 5 -> ChangeLog(changes = current.changes)
                    page == 6 -> StyleEditor(
                        style = current.style,
                        onSave = { text ->
                            save(
                                {
                                    // 内容没变就不打 userEdited：否则「点开看一眼再保存」
                                    // 会永久禁止后续自动归纳。
                                    if (text == it.style.summary) {
                                        it
                                    } else {
                                        it.copy(
                                            style = it.style.copy(
                                                summary = text,
                                                userEdited = true,
                                            )
                                        )
                                    }
                                },
                            ) { page = 0 }
                        },
                        onRestore = {
                            save({ it.copy(style = it.style.copy(userEdited = false)) })
                        },
                    )
                    page == 4 -> AuditProposal(
                        plan = current.pendingAudit,
                        resumeAt = current.progressDone,
                        resumeTotal = current.progressTotal,
                        onResume = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    AiProfileAudit.start(wxId, resume = true)
                                }
                                reload()
                                // 退回概览看进度：提案页没有进度条，续跑时留在原地像卡住了。
                                page = 0
                            }
                        },
                        onApply = { filtered ->
                            scope.launch {
                                appliedChange = withContext(Dispatchers.IO) {
                                    AiProfileAudit.apply(wxId, filtered)
                                }
                                reload()
                                onChanged()
                                page = 0
                            }
                        },
                        onDiscard = {
                            scope.launch {
                                withContext(Dispatchers.IO) { AiProfileAudit.discard(wxId) }
                                reload()
                                onChanged()
                                page = 0
                            }
                        },
                        onClose = { page = 0 },
                    )
                    else -> Overview(
                        profile = current,
                        initializing = initializing,
                        applied = appliedChange,
                        onOpenChanges = { page = 5 },
                        onToggleAutoReply = { value ->
                            save({ it.copy(autoReply = value) })
                        },
                        onToggleSuggest = { value ->
                            save({ it.copy(smartSuggest = value) })
                        },
                        // 首次初始化先做预览：用户确认「多少条 / 预计几片」之后才真正开跑。
                        // 增量整理与审查会动已有档案，才需要二次确认。
                        onStartFull = { plan, fromIndex ->
                            // 上一次的执行结果先收起来：接下来跑的是另一件事，留着会被误读成本次结果。
                            appliedChange = null
                            initializing = true
                            AiProfileInitializer.start(wxId, plan, fromIndex)
                            scope.launch { reload() }
                        },
                        onIncremental = {
                            appliedChange = null
                            confirm = "incremental"
                        },
                        onAudit = {
                            appliedChange = null
                            confirm = "audit"
                        },
                        onOpen = { page = it },
                        onOpenProposal = { page = 4 },
                    )
                }
            }

            when (confirm) {
                "incremental" -> ConfirmAction(
                    title = "增量整理",
                    body = "从 $incrementalFrom 的前一天开始，只读取这之后新增的聊天记录，" +
                        "追加新的标签与事件。已有档案不会被删除。",
                    confirmText = "开始整理",
                    onConfirm = {
                        confirm = null
                        appliedChange = null
                        initializing = true
                        AiProfileInitializer.startIncremental(wxId)
                        scope.launch { reload() }
                    },
                    onDismiss = { confirm = null },
                )
                "audit" -> {
                    // 已有一份没处理的建议时要说清楚：再审查一次会把上一份覆盖掉。
                    // 「执行完又弹出一份」多半就是这里 —— 上一份还没处理，又跑了一次审查。
                    val pending = profile?.pendingAudit?.takeIf { !it.isEmpty }
                    ConfirmAction(
                        title = "审查档案",
                        body = "审查现有标签与事件，只生成清理建议：删除站不住的标签与不重要的事件、" +
                            "把闲聊合并、标出关键事件。建议生成后由你逐条确认才会执行。" +
                            (
                                pending?.let {
                                    "\n\n已有一份未处理的建议（" + (planTime(it) ?: "上次审查") +
                                        " 生成），重新审查会覆盖它。"
                                } ?: ""
                                ),
                        confirmText = "开始审查",
                        onConfirm = {
                            confirm = null
                            AiProfileAudit.start(wxId)
                            scope.launch { reload() }
                        },
                        onDismiss = { confirm = null },
                    )
                }
            }
        }
    }
}

/** 二次确认：与页面同一套视觉，不用系统对话框。 */
@Composable
private fun ConfirmAction(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ScrimDialog(show = true, onDismissRequest = onDismiss, title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                body,
                fontSize = 13.sp,
                color = WxPalette.textSecondary(),
                lineHeight = 19.sp,
            )
            AiPrimaryButton(text = confirmText, onClick = onConfirm)
            AiSecondaryButton(text = "取消", onClick = onDismiss)
        }
    }
}

// ── 概览 ────────────────────────────────────────────────────────────────────

@Composable
private fun Overview(
    profile: AiProfile,
    initializing: Boolean,
    applied: ProfileChange?,
    onOpenChanges: () -> Unit,
    onToggleAutoReply: (Boolean) -> Unit,
    onToggleSuggest: (Boolean) -> Unit,
    onStartFull: (AiProfileInitializer.Plan, Int) -> Unit,
    onIncremental: () -> Unit,
    onAudit: () -> Unit,
    onOpen: (Int) -> Unit,
    onOpenProposal: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProfileHeader(profile)

        when {
            // 整理与审查期间只显示状态：这段时间档案是只读的，开关与跳转都没有意义。
            profile.busy || initializing -> {
                ProgressBlock(profile)
            }
            profile.state == AiProfileState.READY -> {
                profile.pendingAudit?.takeIf { !it.isEmpty }?.let { plan ->
                    ProposalBanner(plan = plan, onClick = onOpenProposal)
                }
                // 刚执行完的结果：当场给一句，说明这次到底动了什么。
                // 不落成常驻文案 —— 历史记录在「变动记录」里，概览页不会一直挂着上一次的结果。
                applied?.let { change ->
                    ResultBanner(change = change, onClick = onOpenChanges)
                }
                ReadyBlock(
                    profile = profile,
                    onToggleAutoReply = onToggleAutoReply,
                    onToggleSuggest = onToggleSuggest,
                    onIncremental = onIncremental,
                    onAudit = onAudit,
                    onOpen = onOpen,
                )
            }
            else -> {
                UninitializedBlock(profile = profile, onStart = onStartFull)
            }        }
    }
}

/** 提案生成时间（分钟精度）；`createdAt` 缺失时返回 null。 */
private fun planTime(plan: AiAuditPlan): String? = minuteOf(plan.createdAt)

/** 审查完成后的提示条：点开就是逐条勾选的建议页。 */
@Composable
private fun ProposalBanner(plan: AiAuditPlan, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WxPalette.primarySoft())
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = "审查完成，${plan.total} 项建议待确认",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = WxPalette.Primary,
        )
        Text(
            text = buildString {
                append(plan.note.ifBlank { "查看准备清理的标签与事件，勾选后执行" })
                planTime(plan)?.let { append(" · ").append(it) }
            },
            fontSize = 12.sp,
            color = WxPalette.textSecondary(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 刚执行完的审查建议：当场说清这次动了什么，点开就是完整历史。
 *
 * 只活在这个弹窗会话里 —— 关掉再打开就没了，概览页不会常驻上一次的执行结果。
 */
@Composable
private fun ResultBanner(change: ProfileChange, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WxPalette.primarySoft())
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = "已执行审查建议",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = WxPalette.Primary,
        )
        Text(
            text = change.detail() + " · 查看变动记录",
            fontSize = 12.sp,
            color = WxPalette.textSecondary(),
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 备注优先，其次昵称，最后 wxId；管理弹窗与列表的显示口径一致。 */
private fun displayName(profile: AiProfile): String =
    profile.remark.ifBlank { profile.nickname.ifBlank { profile.wxId } }

@Composable
private fun ProfileHeader(profile: AiProfile) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AiAvatar(model = profile.avatarUrl, name = displayName(profile), size = 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = displayName(profile),
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.textPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (profile.remark.isNotBlank() && profile.nickname.isNotBlank()) {
                        append(profile.nickname).append(" · ")
                    }
                    append(
                        when (profile.state) {
                            AiProfileState.READY ->
                                profile.memories.size.toString() + " 条记忆 · " +
                                    AiProfileLimits.reviewGuide(profile).memory.name
                            AiProfileState.FAILED -> "初始化失败"
                            AiProfileState.INITIALIZING -> "正在初始化"
                            else -> "未初始化"
                        }
                    )
                },
                fontSize = 12.sp,
                color = WxPalette.textSecondary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            // 未就绪时把微信号放在名字下面：放在页面最底端会跟「关闭」按钮挤在一起，
            // 看不出这条信息属于谁。
            if (profile.wxId.isNotBlank() && !profile.isReady) {
                Text(
                    text = "微信号：" + profile.wxId,
                    fontSize = 11.sp,
                    color = WxPalette.textTertiary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 年份选项里的「全部」：真实年份都 ≥ 1970。 */
private const val ALL_YEARS = 0

@Composable
private fun UninitializedBlock(
    profile: AiProfile,
    onStart: (AiProfileInitializer.Plan, Int) -> Unit,
) {
    val wxId = profile.wxId
    // 每个范围算过一次就留着：一次预览要按年 / 按月查库，来回切范围不该反复等。
    val plans = remember(wxId) { mutableStateMapOf<Int, AiProfileInitializer.Plan>() }
    // 上次选的起始年：失败后重进弹窗必须接着同一份分片，默认回到「全部」会让片号错位。
    // 0 与「全部」同义，和下面的 ALL_YEARS 是同一个哨兵。
    var fromYear by remember(wxId) { mutableIntStateOf(profile.planFromYear) }
    var scanning by remember(wxId) { mutableStateOf(true) }
    var error by remember(wxId) { mutableStateOf<String?>(null) }
    // 失败重试：LaunchedEffect 的键里必须有它，否则同一个范围不会重新跑。
    var attempt by remember(wxId) { mutableIntStateOf(0) }

    LaunchedEffect(wxId, fromYear, attempt) {
        if (plans.containsKey(fromYear)) {
            scanning = false
            return@LaunchedEffect
        }
        scanning = true
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                AiProfileInitializer.preview(wxId, fromYear.takeIf { it != ALL_YEARS })
            }
        }
        result.onSuccess { plans[fromYear] = it }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
        scanning = false
    }

    val plan = plans[fromYear]
    SoftCard {
        Text(
            // 增量整理失败也走 FAILED：这时档案是有的，只是这次没跑完，文案不能再说「还没有档案」。
            if (profile.initializedAt > 0) "上次整理没有完成" else "这个好友还没有档案",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = WxPalette.textPrimary(),
        )
        Text(
            "整理会读取文字聊天记录，由模型整理出标签与记忆。中途可以退出，已整理的部分会保留。",
            fontSize = 12.sp,
            color = WxPalette.textSecondary(),
            lineHeight = 18.sp,
        )
        // 失败原因放在按钮上方：init 失败是「点了没反应」的高发场景，
        // 只写在卡片外面的小字里用户看不到，会以为按钮坏了。
        if (profile.state == AiProfileState.FAILED && profile.lastError != null) {
            Text(
                text = profile.lastError,
                fontSize = 12.sp,
                color = WxPalette.Danger,
                lineHeight = 18.sp,
            )
        }

        when {
            plan != null -> {
                PlanSummary(plan = plan, fromYear = fromYear, scanning = scanning) { fromYear = it }
            }
            error != null -> {
                Text(
                    text = "统计失败：" + error,
                    fontSize = 12.sp,
                    color = WxPalette.Danger,
                    lineHeight = 18.sp,
                )
                AiSecondaryButton(text = "重新统计", onClick = { attempt++ })
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = WxPalette.Primary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "正在统计聊天记录…",
                        fontSize = 13.sp,
                        color = WxPalette.Primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    text = "长历史好友需要十几秒。统计完成前不能开始。",
                    fontSize = 11.sp,
                    color = WxPalette.textTertiary(),
                )
            }
        }

        // 断点续跑：只有「上次确实跑过、且没跑完、预览出的分片数与当时一致」才给入口。
        // 片数对不上说明聊天记录或所选范围变了，续跑会错位，只能从头开始。
        val resumeAt = profile.progressDone
        val canResume = profile.state == AiProfileState.FAILED &&
            resumeAt > 0 &&
            resumeAt < profile.progressTotal &&
            plan != null &&
            plan.slices.size == profile.progressTotal

        if (canResume) {
            Text(
                text = "上次跑到第 ${resumeAt + 1}/${profile.progressTotal} 片时中断。" +
                    "继续不会重跑前面的片；从头开始会重新整理这一段。",
                fontSize = 12.sp,
                color = WxPalette.textSecondary(),
                lineHeight = 18.sp,
            )
            AiPrimaryButton(
                text = "从第 ${resumeAt + 1} 片继续",
                enabled = !scanning,
                onClick = { onStart(plan, resumeAt) },
            )
            AiSecondaryButton(text = "从头开始", onClick = { onStart(plan, 0) })
        } else {
            AiPrimaryButton(
                text = if (profile.state == AiProfileState.FAILED) "重试初始化" else "开始初始化",
                // 统计没跑完、或者这段范围切不出片时，按钮必须不可点 ——
                // 否则「要跑多少」只能等跑起来才知道。
                enabled = !scanning && plan != null && plan.emptyReason == null,
                onClick = { plan?.let { onStart(it, 0) } },
            )
        }
    }
}

/** 预览结果：记录范围、起始年份选项、本次将整理的条数与片数。 */
@Composable
private fun PlanSummary(
    plan: AiProfileInitializer.Plan,
    fromYear: Int,
    scanning: Boolean,
    onPickYear: (Int) -> Unit,
) {
    Text("聊天记录", fontSize = 12.sp, color = WxPalette.textSecondary())
    Text(
        text = AiPrompts.dateOf(plan.allFromSec) + " ~ " + AiPrompts.dateOf(plan.toSec - 1) +
            " · 共 " + countText(plan.allTotalLines) + " 条",
        fontSize = 13.sp,
        color = WxPalette.textPrimary(),
    )

    if (plan.years.size > 1) {
        Text("从哪一年开始整理", fontSize = 12.sp, color = WxPalette.textSecondary())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            YearChip(
                text = "全部",
                selected = fromYear == ALL_YEARS,
                enabled = !scanning,
            ) { onPickYear(ALL_YEARS) }
            plan.years.forEach { year ->
                YearChip(
                    text = year.toString(),
                    selected = fromYear == year,
                    enabled = !scanning,
                ) { onPickYear(year) }
            }
        }
    }

    if (plan.emptyReason != null) {
        Text(
            text = plan.emptyReason,
            fontSize = 12.sp,
            color = WxPalette.Danger,
            lineHeight = 18.sp,
        )
    } else {
        Text("本次整理", fontSize = 12.sp, color = WxPalette.textSecondary())
        Text(
            text = AiPrompts.dateOf(plan.fromSec) + " 起 · " + countText(plan.totalLines) +
                " 条 · 预计 " + plan.slices.size + " 片",
            fontSize = 13.sp,
            color = WxPalette.textPrimary(),
        )
        Text(
            // 数字从常量取，不写死：上限改过一次，写死的那个还停在 100 / 500。
            text = "片数越多，标签与记忆越容易到上限" +
                "（${AiTools.MAX_TAGS} / ${AiTools.MAX_MEMORIES}）。",
            fontSize = 11.sp,
            color = WxPalette.textTertiary(),
        )
    }
}

@Composable
private fun YearChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) WxPalette.Primary
                else WxPalette.insetSurface().copy(alpha = if (enabled) 1f else 0.5f)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (selected) Color.White else WxPalette.textSecondary(),
        )
    }
}

/** 条数的千分位：`48,236` 比 `48236` 一眼能看出量级。 */
private fun countText(value: Int): String =
    String.format(java.util.Locale.CHINA, "%,d", value)

@Composable
private fun ProgressBlock(profile: AiProfile) {
    val auditing = profile.auditing
    SoftCard {
        Text(
            text = if (auditing) "正在审查档案" else "正在整理档案",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = WxPalette.textPrimary(),
        )
        if (auditing) {
            Text(
                "模型正在核对标签与事件，完成后会列出清理建议。可以关掉这个窗口，后台继续。",
                fontSize = 12.sp,
                color = WxPalette.textSecondary(),
                lineHeight = 18.sp,
            )
        } else {
            Text(
                text = buildString {
                    val label = profile.progress.ifBlank { "准备中" }
                    append(label)
                    if (profile.progressTotal > 0) {
                        append(" · 第 ").append((profile.progressDone + 1).coerceAtMost(profile.progressTotal))
                        append("/").append(profile.progressTotal).append(" 片")
                    }
                },
                fontSize = 13.sp,
                color = WxPalette.Primary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "已整理 ${profile.tags.size} 个标签、${profile.memories.size} 条记忆。可以关掉这个窗口，后台继续。",
                fontSize = 12.sp,
                color = WxPalette.textSecondary(),
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun ReadyBlock(
    profile: AiProfile,
    onToggleAutoReply: (Boolean) -> Unit,
    onToggleSuggest: (Boolean) -> Unit,
    onIncremental: () -> Unit,
    onAudit: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    // 首次整理可能只选了一段范围：写清「这份档案是基于哪段时间的对话」，
    // 否则用户看到标签不全时会以为是漏整理了。
    if (profile.sourceRange.isNotBlank()) {
        Text(
            text = "整理范围：" + profile.sourceRange,
            fontSize = 11.sp,
            color = WxPalette.textTertiary(),
            modifier = Modifier.padding(start = 4.dp),
        )
    }
    Text("功能", fontSize = 12.sp, color = WxPalette.textSecondary(), modifier = Modifier.padding(start = 4.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WxPalette.cardBackground())
    ) {
        AiSwitchRow(
            title = "自动回复",
            checked = profile.autoReply,
            onCheckedChange = onToggleAutoReply,
            subtitle = "收到这位好友的消息时由 AI 代回（复用智能建议的判断）",
        )
        AiSwitchRow(
            title = "智能建议",
            checked = profile.smartSuggest,
            onCheckedChange = onToggleSuggest,
            subtitle = "在聊天页双击屏幕，给出解读与三条可发回复",
            showDivider = false,
        )
    }

    // 档案体积是每次请求都要带上的固定开销：和「档案」标题同一行，不再单独占一行。
    // 键用列表本身而不是条数：改一条标签的值、或给记忆补一段详情时条数不变，
    // 只按数量缓存会一直停在旧数字上。侧写也要进键 —— 否则改完 summary 这里不刷新。
    val tokens = remember(profile.tags, profile.memories, profile.style.summary) {
        AiPrompts.profileTokens(profile)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("档案", fontSize = 12.sp, color = WxPalette.textSecondary())
        Spacer(Modifier.weight(1f))
        Text(
            text = "约 " + AiTokens.format(tokens) + " token",
            fontSize = 11.sp,
            color = WxPalette.textTertiary(),
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WxPalette.cardBackground())
    ) {
        AiNavRow(
            title = "聊天目标",
            trailing = goalLine(profile),
            onClick = { onOpen(1) },
        )
        // 前两行是「我方设定」，后三行是「对方档案」：侧写描述的是用户，所以紧跟在聊天目标之后。
        AiNavRow(
            title = "回复风格",
            trailing = styleLine(profile.style),
            onClick = { onOpen(6) },
        )
        AiNavRow(
            title = "标签管理",
            trailing = profile.tags.size.toString() + " 个",
            onClick = { onOpen(2) },
        )
        AiNavRow(
            title = "记忆管理",
            trailing = profile.memories.size.toString() + " 条",
            onClick = { onOpen(3) },
        )
        AiNavRow(
            title = "变动记录",
            trailing = profile.changes.lastOrNull()?.let { changeTime(it.at) } ?: "暂无",
            onClick = { onOpen(5) },
            showDivider = false,
        )
    }

    // 两种维护动作并排：增量整理只补新记录，审查只提清理建议。
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AiSecondaryButton(
            text = "增量整理",
            onClick = onIncremental,
            modifier = Modifier.weight(1f),
        )
        AiSecondaryButton(
            text = "审查档案",
            onClick = onAudit,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun goalLine(profile: AiProfile): String {
    val goal = profile.goal ?: return "未设置"
    return buildString {
        if (goal.relation.isNotBlank()) append(goal.relation)
        if (goal.purpose.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append(goal.purpose)
        }
    }.ifBlank { "未设置" }
}

/** 档案页统一的时间文案（分钟精度）；`at` 缺失时返回 null，由调用方决定写「暂无」还是省略。 */
private fun minuteOf(at: Long): String? =
    if (at <= 0) null
    else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        .format(java.util.Date(at))

/** 变动记录的时间；没记时间的老条目给「暂无」。 */
private fun changeTime(at: Long): String = minuteOf(at) ?: "暂无"

/** 「回复风格」行的右侧文案：只放一句预览，不在这里编辑（侧写几百字塞不进一行）。 */
private fun styleLine(style: ReplyStyle): String {
    if (style.summary.isBlank()) return if (style.insufficient) "样本不足" else "未归纳"
    val first = AiPrompts.styleHeadline(style.summary).ifBlank { style.summary.trim() }
    return first.take(14) + if (first.length > 14) "…" else ""
}

// ── 回复风格 ────────────────────────────────────────────────────────────────

/**
 * 侧写编辑页：**只改这一段文本**，结构化字段一个都没有。
 *
 * 分段（【整体】【消息形态】…）是给模型定位用的，用户在同一个输入框里改就行 ——
 * 拆成独立的表单会让「改一处、另一处没变」成为常态。
 *
 * 字数不设上限：场景多的人格本来就长，硬截断会把场景压掉。只显示 token 占用，
 * 让用户自己判断值不值。
 */
@Composable
private fun StyleEditor(
    style: ReplyStyle,
    onSave: (String) -> Unit,
    onRestore: () -> Unit,
) {
    var text by remember(style.summary) { mutableStateOf(style.summary) }
    val trimmed = text.trim()
    val tokens = remember(trimmed) { AiTokens.estimate(trimmed) }

    Text(
        text = "每一条都要能改变一句话的写法；背景故事不算。",
        fontSize = 11.sp,
        color = WxPalette.textTertiary(),
        lineHeight = 16.sp,
    )

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(WxPalette.insetSurface())
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (text.isEmpty()) {
            Text(
                "还没有归纳。跑一次全量整理，或在下面自己写一段。",
                fontSize = 14.sp,
                color = WxPalette.textTertiary(),
                lineHeight = 21.sp,
            )
        }
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(fontSize = 14.sp, color = WxPalette.textPrimary(), lineHeight = 21.sp),
            cursorBrush = SolidColor(WxPalette.Primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = trimmed.length.toString() + " 字 · 约 " + AiTokens.format(tokens) + " token",
            fontSize = 11.sp,
            color = WxPalette.textTertiary(),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "每次生成都会带上",
            fontSize = 11.sp,
            color = WxPalette.textTertiary(),
        )
    }

    val basis = when {
        style.insufficient -> "本人发言太少，暂不归纳"
        style.basedUntil.isNotBlank() -> "基于截止 ${style.basedUntil} 的记录"
        else -> "还没有归纳过"
    }
    Text(text = basis, fontSize = 11.sp, color = WxPalette.textTertiary())

    AiPrimaryButton(
        text = "保存",
        enabled = trimmed.isNotEmpty() && trimmed != style.summary,
        onClick = { onSave(trimmed) },
    )

    if (style.userEdited) {
        AiSecondaryButton(text = "恢复自动归纳", onClick = onRestore)
        Text(
            text = "下次全量整理时会重新写这一段。",
            fontSize = 11.sp,
            color = WxPalette.textTertiary(),
        )
    }
}

// ── 变动记录 ────────────────────────────────────────────────────────────────

/**
 * 档案变动历史：每次整理 / 审查一行，最新在上。
 *
 * 只显示计数（新增/更新/删除/合并/标记），不展开条目 —— 记录用来回答「这段时间它变了什么」，
 * 想看当前内容去标签页与记忆页。
 */
@Composable
private fun ChangeLog(changes: List<ProfileChange>) {
    if (changes.isEmpty()) {
        SoftCard {
            Text(
                "还没有整理或审查记录",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.textPrimary(),
            )
            Text(
                "首次整理、增量整理、审查执行之后，这里会记下每次改动了多少。",
                fontSize = 12.sp,
                color = WxPalette.textSecondary(),
                lineHeight = 18.sp,
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        changes.asReversed().forEach { change ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(WxPalette.cardBackground())
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = changeTime(change.at),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = WxPalette.textPrimary(),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = change.source,
                        fontSize = 11.sp,
                        color = WxPalette.Primary,
                    )
                }
                Text(
                    text = change.detail(),
                    fontSize = 12.sp,
                    color = WxPalette.textSecondary(),
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

// ── 审查建议 ────────────────────────────────────────────────────────────────

/**
 * 提案页：模型只写「准备删什么、合并什么、标什么」+ 理由，这里由用户逐条勾选。
 *
 * 默认全选、可取消 —— 模型多数建议是对的，逐条勾反而增加操作；但它判断错的那些
 * 必须能一键去掉，所以每条都独立可选。
 */
@Composable
private fun AuditProposal(
    plan: AiAuditPlan?,
    resumeAt: Int = 0,
    resumeTotal: Int = 0,
    onResume: () -> Unit = {},
    onApply: (AiAuditPlan) -> Unit,
    onDiscard: () -> Unit,
    onClose: () -> Unit,
) {
    if (plan == null || plan.isEmpty) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "没有待确认的建议。",
                fontSize = 13.sp,
                color = WxPalette.textSecondary(),
                lineHeight = 19.sp,
            )
            AiSecondaryButton(text = "返回", onClick = onClose)
        }
        return
    }

    var selTagGroups by remember(plan.createdAt) { mutableStateOf(plan.mergeTags.indices.toSet()) }
    var selTags by remember(plan.createdAt) { mutableStateOf(plan.deleteTags.indices.toSet()) }
    var selMemories by remember(plan.createdAt) { mutableStateOf(plan.deleteMemories.indices.toSet()) }
    var selGroups by remember(plan.createdAt) { mutableStateOf(plan.mergeGroups.indices.toSet()) }
    var selMarks by remember(plan.createdAt) { mutableStateOf(plan.markImportant.indices.toSet()) }
    val selected = selTagGroups.size + selTags.size + selMemories.size + selGroups.size + selMarks.size

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (plan.note.isNotBlank()) {
            Text(
                plan.note,
                fontSize = 13.sp,
                color = WxPalette.textSecondary(),
                lineHeight = 19.sp,
            )
        }
        Text(
            "默认全选，取消勾选的项不会执行。执行只改本地档案，不再请求模型。" +
                (planTime(plan)?.let { "这份建议生成于 $it，档案被手动改过的条目会自动跳过。" } ?: ""),
            fontSize = 11.sp,
            color = WxPalette.textTertiary(),
            lineHeight = 16.sp,
        )

        // 中断的审查：已提交的建议保留，继续只处理剩下的片。判断同 UninitializedBlock：
        // 1 ≤ 已处理片数 < 总片数，正常完成时这两个字段已被清零。
        if (resumeAt in 1 until resumeTotal) {
            Text(
                text = "上次审查跑到第 $resumeAt/$resumeTotal 片时中断。" +
                    "继续只处理剩下的片，已提交的建议不会重复。",
                fontSize = 12.sp,
                color = WxPalette.textSecondary(),
                lineHeight = 18.sp,
            )
            AiSecondaryButton(
                text = "继续审查（从第 ${resumeAt + 1} 片）",
                onClick = onResume,
            )
        }

        if (plan.mergeTags.isNotEmpty()) {
            ProposalSection("合并标签（${plan.mergeTags.size} 组）")
            ProposalRows(plan.mergeTags.size, plan.createdAt) { index ->
                val group = plan.mergeTags[index]
                ProposalRow(
                    title = group.category + "/" + group.key + " = " + group.value,
                    subtitle = buildString {
                        if (group.reason.isNotBlank()) append(group.reason).append("。")
                        append("合并 ${group.keys.size} 个键：")
                        append(group.keys.joinToString("、"))
                    },
                    checked = index in selTagGroups,
                    onToggle = { selTagGroups = selTagGroups.toggle(index) },
                )
            }
        }

        if (plan.deleteTags.isNotEmpty()) {
            ProposalSection("删除标签（${plan.deleteTags.size}）")
            ProposalRows(plan.deleteTags.size, plan.createdAt) { index ->
                val ref = plan.deleteTags[index]
                ProposalRow(
                    title = ref.category + "/" + ref.key,
                    subtitle = ref.reason,
                    checked = index in selTags,
                    onToggle = { selTags = selTags.toggle(index) },
                )
            }
        }

        if (plan.deleteMemories.isNotEmpty()) {
            ProposalSection("删除事件（${plan.deleteMemories.size}）")
            ProposalRows(plan.deleteMemories.size, plan.createdAt) { index ->
                val ref = plan.deleteMemories[index]
                ProposalRow(
                    title = AiDates.display(ref.occurredAt) + " " + ref.title,
                    subtitle = ref.reason,
                    checked = index in selMemories,
                    onToggle = { selMemories = selMemories.toggle(index) },
                )
            }
        }

        if (plan.mergeGroups.isNotEmpty()) {
            ProposalSection("合并事件（${plan.mergeGroups.size} 组）")
            ProposalRows(plan.mergeGroups.size, plan.createdAt) { index ->
                val group = plan.mergeGroups[index]
                ProposalRow(
                    title = group.title + "（合并 ${group.items.size} 条）",
                    subtitle = buildString {
                        if (group.reason.isNotBlank()) append(group.reason).append("。")
                        append("包含：")
                        append(group.items.joinToString("、") { it.title })
                    },
                    checked = index in selGroups,
                    onToggle = { selGroups = selGroups.toggle(index) },
                )
            }
        }

        if (plan.markImportant.isNotEmpty()) {
            ProposalSection("标记关键事件（${plan.markImportant.size}）")
            ProposalRows(plan.markImportant.size, plan.createdAt) { index ->
                val ref = plan.markImportant[index]
                ProposalRow(
                    title = AiDates.display(ref.occurredAt) + " " + ref.title,
                    subtitle = if (ref.reason.isBlank()) "标记为关键事件" else "标记为关键事件 · " + ref.reason,
                    checked = index in selMarks,
                    onToggle = { selMarks = selMarks.toggle(index) },
                    accent = true,
                )
            }
        }

        Spacer(Modifier.height(2.dp))
        AiPrimaryButton(
            text = "执行选中项（$selected）",
            enabled = selected > 0,
            onClick = {
                onApply(
                    plan.copy(
                        mergeTags = plan.mergeTags.filterIndexed { i, _ -> i in selTagGroups },
                        deleteTags = plan.deleteTags.filterIndexed { i, _ -> i in selTags },
                        deleteMemories = plan.deleteMemories.filterIndexed { i, _ -> i in selMemories },
                        mergeGroups = plan.mergeGroups.filterIndexed { i, _ -> i in selGroups },
                        markImportant = plan.markImportant.filterIndexed { i, _ -> i in selMarks },
                    )
                )
            },
        )
        AiSecondaryButton(text = "全部忽略", onClick = onDiscard, color = WxPalette.Danger)
    }
}

/** 提案每段默认先渲染这么多条：14 片累计最多 1500 条删除，全量组合会明显卡顿。 */
private const val PROPOSAL_PREVIEW = 20

/**
 * 一段提案的行。
 *
 * 不换 `LazyColumn`：外层是 `ScrimDialog` 的 `verticalScroll`，嵌套滚动会互相抢手势。
 * 折叠到 20 条足够判断「这一批是删什么的」，要看全部再展开。
 */
@Composable
private fun ProposalRows(total: Int, key: Any?, content: @Composable (index: Int) -> Unit) {
    var expanded by remember(key) { mutableStateOf(false) }
    val shown = if (expanded) total else minOf(total, PROPOSAL_PREVIEW)
    for (index in 0 until shown) content(index)
    if (!expanded && total > PROPOSAL_PREVIEW) {
        Text(
            text = "展开全部（还有 ${total - PROPOSAL_PREVIEW} 条）",
            fontSize = 12.sp,
            color = WxPalette.textSecondary(),
            modifier = Modifier
                .clickable { expanded = true }
                .padding(vertical = 8.dp),
        )
    }
}

private fun Set<Int>.toggle(index: Int): Set<Int> =
    if (contains(index)) this - index else this + index

@Composable
private fun ProposalSection(title: String) {
    Text(
        title,
        fontSize = 12.sp,
        color = WxPalette.textSecondary(),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun ProposalRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
    accent: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(WxPalette.cardBackground())
            .clickable { onToggle() }
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = WxPalette.Primary),
        )
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(
                title,
                fontSize = 14.sp,
                color = if (accent) WxPalette.Primary else WxPalette.textPrimary(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = WxPalette.textTertiary(),
                    lineHeight = 16.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

// ── 聊天目标 ────────────────────────────────────────────────────────────────

private val RELATIONS = listOf("朋友", "同事", "同学", "家人", "暧昧", "恋人", "其他")

@Composable
private fun GoalEditor(profile: AiProfile, onSave: (ChatGoal) -> Unit) {
    var relation by remember(profile.wxId) { mutableStateOf(profile.goal?.relation.orEmpty()) }
    var purpose by remember(profile.wxId) { mutableStateOf(profile.goal?.purpose.orEmpty()) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("关系", fontSize = 12.sp, color = WxPalette.textSecondary())
        Row(
            Modifier.fillMaxWidth().horizontalScrollCompat(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RELATIONS.forEach { item ->
                val selected = relation == item
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(if (selected) WxPalette.primarySoft() else WxPalette.insetSurface())
                        .clickable { relation = if (selected) "" else item }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        item,
                        fontSize = 13.sp,
                        color = if (selected) WxPalette.Primary else WxPalette.textSecondary(),
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }

        Text("聊天目的", fontSize = 12.sp, color = WxPalette.textSecondary())
        AiTextInput(
            value = purpose,
            onValueChange = { purpose = it },
            placeholder = "例如：想更多地了解她，慢慢推进关系",
            singleLine = false,
            minHeight = 72,
        )

        val stage = profile.goal?.stage.orEmpty()
        if (stage.isNotBlank()) {
            Text(
                "模型判断的当前进度：" + stage,
                fontSize = 12.sp,
                color = WxPalette.textSecondary(),
            )
        }

        Text(
            "这段描述会随每次请求一起发给模型。关系与目的写得越具体，建议越接近你真实的想法。",
            fontSize = 11.sp,
            color = WxPalette.textTertiary(),
            lineHeight = 16.sp,
        )

        AiPrimaryButton(
            text = "保存",
            onClick = {
                onSave(
                    ChatGoal(
                        relation = relation,
                        purpose = purpose.trim(),
                        stage = profile.goal?.stage.orEmpty(),
                    )
                )
            },
        )
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier =
    this.then(
        Modifier.horizontalScroll(rememberScrollState())
    )

// ── 标签 ────────────────────────────────────────────────────────────────────

private val TAG_ORDER = listOf("基础", "关系", "兴趣", "家庭", "性格", "作息", "忌口", "工作")

@Composable
private fun TagEditor(profile: AiProfile, onUpdate: (List<ProfileTag>) -> Unit) {
    var editing by remember { mutableStateOf<ProfileTag?>(null) }
    var editValue by remember { mutableStateOf("") }

    if (profile.tags.isEmpty()) {
        Text(
            "还没有标签。初始化时模型会按聊天记录自动补上，之后你也可以删掉不准的。",
            fontSize = 13.sp,
            color = WxPalette.textSecondary(),
            lineHeight = 19.sp,
        )
        return
    }

    val grouped = remember(profile.tags) {
        val byCategory = profile.tags.groupBy { it.category }
        val ordered = mutableListOf<Pair<String, List<ProfileTag>>>()
        TAG_ORDER.forEach { name -> byCategory[name]?.let { ordered += name to it } }
        byCategory.filterKeys { it !in TAG_ORDER }.forEach { (name, list) -> ordered += name to list }
        ordered
    }

    editing?.let { tag ->
        TagValueDialog(
            tag = tag,
            value = editValue,
            onValueChange = { editValue = it },
            onDismiss = { editing = null },
            onConfirm = {
                onUpdate(
                    profile.tags.map {
                        if (it.id == tag.id) it.copy(value = editValue.trim()) else it
                    }
                )
                editing = null
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        grouped.forEach { (category, tags) ->
            Text(category, fontSize = 12.sp, color = WxPalette.textSecondary())
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(WxPalette.cardBackground())
            ) {
                tags.forEachIndexed { index, tag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    editing = tag
                                    editValue = tag.value
                                }
                        ) {
                            Text(
                                tag.key,
                                fontSize = 11.sp,
                                color = WxPalette.textSecondary(),
                            )
                            Text(
                                tag.value,
                                fontSize = 14.sp,
                                color = WxPalette.textPrimary(),
                                modifier = Modifier.padding(top = 1.dp),
                            )
                            if (tag.evidence.isNotBlank()) {
                                Text(
                                    "「" + tag.evidence + "」",
                                    fontSize = 11.sp,
                                    color = WxPalette.textTertiary(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        Text(
                            "删除",
                            fontSize = 12.sp,
                            color = WxPalette.Danger,
                            modifier = Modifier
                                .clickable { onUpdate(profile.tags.filterNot { it.id == tag.id }) }
                                .padding(8.dp),
                        )
                    }
                    if (index != tags.lastIndex) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp)
                                .height(0.5.dp)
                                .background(WxPalette.divider())
                        )
                    }
                }
            }
        }
        Text(
            "标签由 AI 读取聊天记录生成，点条目可以改值。写得不准的删掉即可，下次整理会重新判断。",
            fontSize = 11.sp,
            color = WxPalette.textTertiary(),
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun TagValueDialog(
    tag: ProfileTag,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    BackHandler { onDismiss() }
    ScrimDialog(show = true, onDismissRequest = onDismiss, title = tag.key) {
        AiTextInput(value = value, onValueChange = onValueChange, placeholder = "标签内容")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AiSecondaryButton("取消", onDismiss, Modifier.weight(1f))
            Box(Modifier.weight(1f)) {
                AiPrimaryButton("保存", onConfirm, enabled = value.isNotBlank())
            }
        }
    }
}

// ── 记忆 ────────────────────────────────────────────────────────────────────

@Composable
private fun MemoryList(profile: AiProfile) {
    if (profile.memories.isEmpty()) {
        Text(
            "还没有记忆条目。初始化时模型会把聊过的事按时间整理进来。",
            fontSize = 13.sp,
            color = WxPalette.textSecondary(),
            lineHeight = 19.sp,
        )
        return
    }
    var expanded by remember { mutableStateOf<String?>(null) }
    // 序号按时间正序算（#1 是最早的事件），列表按最新在上展示：把整份倒过来即可，
    // 不必为「排序」和「取序号」各排一遍序。
    val rows = remember(profile.memories) { AiMemoryIndex.numbered(profile.memories).asReversed() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (number, memory) ->
            MemoryRow(
                memory = memory,
                number = number,
                expanded = expanded == memory.id,
                onClick = { expanded = if (expanded == memory.id) null else memory.id },
            )
        }
    }
}

@Composable
private fun MemoryRow(memory: MemoryEntry, number: Int, expanded: Boolean, onClick: () -> Unit) {
    // 关键事件用一条微信绿竖条标出来：列表里一眼能分出「吵架/第一次」和日常闲聊。
    // 用 drawBehind 画而不是「3dp 子 View + height(IntrinsicSize.Min)」：后者会让每一行多跑一遍
    // intrinsic 测量（500 条记忆就是 500 次额外测量），而这里只是画一条矩形。
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WxPalette.cardBackground())
            .drawBehind {
                if (memory.important) {
                    drawRect(WxPalette.Primary, size = Size(3.dp.toPx(), size.height))
                }
            }
            .clickable { onClick() }
    ) {
        Column(
            Modifier
                .weight(1f)
                // 竖条占的 3dp 由左内边距让出来，视觉与之前的子 View 一致。
                .padding(
                    start = if (memory.important) 17.dp else 14.dp,
                    top = 11.dp,
                    end = 14.dp,
                    bottom = 11.dp,
                )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 序号是档案里的 #N（按时间正序），模型按它读详情，用户也按它指认。
                Text(
                    text = "#$number",
                    fontSize = 11.sp,
                    color = WxPalette.textTertiary(),
                )
                Text(
                    text = AiDates.display(memory.occurredAt),
                    fontSize = 11.sp,
                    color = WxPalette.textTertiary(),
                    modifier = Modifier.padding(start = 6.dp),
                )
                if (memory.important) {
                    Text(
                        text = "关键",
                        fontSize = 10.sp,
                        color = WxPalette.Primary,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(WxPalette.primarySoft())
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = memory.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (memory.important) WxPalette.Primary else WxPalette.textPrimary(),
                modifier = Modifier.padding(top = 2.dp),
            )
            if (expanded && memory.detail.isNotBlank()) {
                Text(
                    text = memory.detail,
                    fontSize = 12.sp,
                    color = WxPalette.textSecondary(),
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else if (memory.detail.isNotBlank()) {
                Text(
                    text = memory.detail,
                    fontSize = 12.sp,
                    color = WxPalette.textSecondary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
