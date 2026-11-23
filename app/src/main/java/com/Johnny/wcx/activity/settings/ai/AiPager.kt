package com.Johnny.wcx.activity.settings.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Bug_report
import com.Johnny.wcx.activity.settings.CONTENT_BOTTOM_INSET
import com.Johnny.wcx.ai.AiLetterIndex
import com.Johnny.wcx.ai.AiProfileState
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.ai.AiImportantAlerts
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.ai.group.GroupDigestScheduler
import com.Johnny.wcx.ai.group.GroupDigestStore
import com.Johnny.wcx.ai.group.GroupSummary
import com.Johnny.wcx.ai.AiProfileSummary
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.ui.style.SoftCard
import com.Johnny.wcx.ui.content.ScrimDialog
import com.Johnny.wcx.features.items.chat.AiMomentsCopy
import com.Johnny.wcx.ui.content.AiConfigPanel
import com.Johnny.wcx.ui.style.WxPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 页：好友档案列表（群组一期只放占位）。
 *
 * 数据全部来自 [AiProfileStore] 的两个文件（`index.json` + `friends.json`），
 * 页面本身不查数据库 —— 查库只在「添加好友」「初始化」这些明确的动作里发生。
 */
@Composable
internal fun AiPager(
    /** 外部（例如从别处回到本页）触发的刷新计数：变化即重读索引。 */
    externalRefresh: Int = 0,
    /** 设置页从导航栈进入时给返回按钮用；独立使用时传 null 不显示。 */
    onBack: (() -> Unit)? = null,
) {
    var tab by remember { mutableIntStateOf(0) }
    var summaries by remember { mutableStateOf<List<AiProfileSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var openWxId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<AiProfileSummary?>(null) }
    var groupSummaries by remember { mutableStateOf<List<GroupSummary>>(emptyList()) }
    var openGroupId by remember { mutableStateOf<String?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<GroupSummary?>(null) }
    var showAddGroup by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    // debug 提示词面板：入口在「添加」左边，默认收起（面板内每段也各自收起）。
    var showPrompts by remember { mutableStateOf(false) }
    // 「添加好友」浮层：以前由外层 SettingsActivity 渲染，WCX 接线时没接上 → 点添加没反应。
    // 现在由本页自己渲染，跟「添加群」一样内部闭环，AI 相关的一切都在这一页里。
    var showAddFriend by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 列表只读 index.json：好友快照由「添加好友」浮层自己按需刷新，页面不该为它卡住。
    LaunchedEffect(refreshKey, externalRefresh) {
        withContext(Dispatchers.IO) {
            summaries = AiProfileStore.loadIndex().profiles
            groupSummaries = GroupDigestStore.loadIndex().groups
        }
        loading = false
    }

    fun reload() {
        refreshKey++
    }

    // 页面底色必须自己铺：底部导航条用 layerBackdrop 采样当前页内容做毛玻璃，
    // 页面透明时采样到的是空图层，模糊后整条导航变成黑色（浅色模式下尤其明显）。
    // 其它页都通过 PageScaffold / MiuixListScaffold 铺了同一个底色。
    Box(
        Modifier
            .fillMaxSize()
            .background(WxPalette.pageBackground())
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            AiHeader(
                tab = tab,
                onBack = onBack,
                onTabChange = { tab = it },
                onAdd = { if (tab == 1) showAddGroup = true else showAddFriend = true },
                onPrompts = if (BuildConfig.DEBUG) {
                    { showPrompts = true }
                } else {
                    null
                },
            )

            // 总结 / 说明 / 设置都不依赖好友档案索引，不进「载入中」这条分支。
            if (tab == 2) {
                AiReportPage()
            } else if (tab == 3) {
                AiHelpPage(
                    onOpenConfig = { showConfig = true },
                    onMomentsCopy = { AiMomentsCopy.showDialog(context) },
                )
            } else if (tab == 4) {
                AiCueSettings(onOpenConfig = { showConfig = true })
            } else if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("载入中…", fontSize = 13.sp, color = WxPalette.textSecondary())
                }
            } else if (tab == 1) {
                GroupDigestList(
                    groups = groupSummaries,
                    onOpen = { openGroupId = it },
                    onLongPress = { deleteGroupTarget = it },
                    onAdd = { showAddGroup = true },
                )
            } else {
                val alerts = remember(refreshKey) { AiImportantAlerts.pending() }
                if (alerts.isNotEmpty()) {
                    ImportantAlertCard(
                        alerts = alerts,
                        onDismiss = {
                            AiImportantAlerts.markAllRead()
                            toast = "已标记全部重要信息为「知道了」"
                            reload()
                        },
                    )
                }
                if (!AiConfig.isConfigured) {
                    ConfigureBanner(onClick = { showConfig = true })
                }
                AiFriendList(
                    summaries = summaries,
                    onOpen = { openWxId = it },
                    onLongPress = { deleteTarget = it },
                    onAdd = { showAddFriend = true },
                )
            }
        }

        openWxId?.let { wxId ->
            AiProfileDialog(
                wxId = wxId,
                onDismiss = { openWxId = null },
                onChanged = { reload() },
            )
        }

        openGroupId?.let { groupId ->
            AiGroupDialog(
                groupId = groupId,
                onDismiss = { openGroupId = null },
                onChanged = { reload() },
            )
        }

        deleteGroupTarget?.let { target ->
            ScrimDialog(
                show = true,
                onDismissRequest = { deleteGroupTarget = null },
                title = "删除群总结",
            ) {
                Text(
                    text = "同时删除该群的全部历史摘要与定时任务，无法恢复。",
                    fontSize = 13.sp,
                    color = WxPalette.textSecondary(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AiSecondaryButton(
                        text = "取消",
                        onClick = { deleteGroupTarget = null },
                        modifier = Modifier.weight(1f),
                    )
                    AiPrimaryButton(
                        text = "删除",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val groupId = target.groupId
                            deleteGroupTarget = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    GroupDigestScheduler.cancel(groupId)
                                    GroupDigestStore.deleteConfig(groupId)
                                }
                                toast = "已删除群总结"
                                reload()
                            }
                        },
                    )
                }
            }
        }

        if (showAddGroup) {
            AiAddGroupSheet(
                existingIds = groupSummaries.mapTo(HashSet()) { it.groupId },
                onDismiss = { showAddGroup = false },
                onRequestConfig = {
                    showAddGroup = false
                    showConfig = true
                },
                onAdded = { count ->
                    showAddGroup = false
                    toast = if (count > 0) "已添加 $count 个群" else "没有添加"
                    reload()
                },
            )
        }

        deleteTarget?.let { target ->
            DeleteProfileDialog(
                summary = target,
                onDismiss = { deleteTarget = null },
                onConfirm = {
                    val wxId = target.wxId
                    deleteTarget = null
                    scope.launch {
                        withContext(Dispatchers.IO) { AiProfileStore.deleteProfile(wxId) }
                        toast = "已删除档案"
                        reload()
                    }
                },
            )
        }

        if (showAddFriend) {
            AiAddFriendSheet(
                onDismiss = { showAddFriend = false },
                onRequestConfig = {
                    showAddFriend = false
                    showConfig = true
                },
                onConfirmed = { created ->
                    showAddFriend = false
                    toast = if (created > 0) "已添加 $created 位好友，点进档案选起始年份开始整理" else "没有新增（已在列表中）"
                    reload()
                },
            )
        }

        ScrimDialog(
            show = showConfig,
            onDismissRequest = { showConfig = false },
            title = "AI 配置",
        ) {
            AiConfigPanel(
                modifier = Modifier.heightIn(max = 520.dp),
                onDismiss = { showConfig = false },
                onSaved = {
                    toast = "AI 接口已保存"
                    reload()
                },
            )
        }

        toast?.let { message ->
            AiToast(message = message, onDismiss = { toast = null })
        }

        if (showPrompts) {
            AiPromptSheet(onDismiss = { showPrompts = false })
        }
    }
}

// ── 顶部 ────────────────────────────────────────────────────────────────────

@Composable
private fun AiHeader(
    tab: Int,
    onBack: (() -> Unit)? = null,
    onTabChange: (Int) -> Unit,
    onAdd: () -> Unit,
    /** debug 专用：null 表示不显示入口（release 包）。 */
    onPrompts: (() -> Unit)? = null,
) {
    // 不再单独放「AI」大标题：导航栏已经写了 AI，标题只是重复占一行；
    // 两个分段与「添加」同排，顶部空间省下约 40dp 给列表。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            Icon(
                imageVector = MaterialSymbols.Outlined.Arrow_back,
                contentDescription = "返回",
                tint = WxPalette.textPrimary(),
                modifier = Modifier
                    .size(22.dp)
                    .clickable { onBack() },
            )
            Spacer(Modifier.width(2.dp))
        }
        // 四个分段可能比屏幕宽：放进可横向滚动的容器，右侧「添加」始终可见。
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SegmentPill("好友", tab == 0) { onTabChange(0) }
            SegmentPill("群组", tab == 1) { onTabChange(1) }
            SegmentPill("总结", tab == 2) { onTabChange(2) }
            SegmentPill("说明", tab == 3) { onTabChange(3) }
            SegmentPill("设置", tab == 4) { onTabChange(4) }
        }
        // 顺序：提示词（仅 debug）在左，添加在右。
        if (onPrompts != null) {
            AiIconPill(
                icon = MaterialSymbols.Outlined.Bug_report,
                text = "提示词",
                onClick = onPrompts,
            )
        }
        if (tab == 0 || tab == 1) {
            AiIconPill(
                icon = MaterialSymbols.Outlined.Add,
                text = if (tab == 1) "添加群" else "添加",
                onClick = onAdd,
            )
        }
    }
}

@Composable
private fun SegmentPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) WxPalette.primarySoft() else WxPalette.insetSurface())
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) WxPalette.Primary else WxPalette.textSecondary(),
        )
    }
}

/**
 * 重要信息提醒卡：自动回复过程中识别到的「别漏」内容。
 *
 * 只显示最近几条，点「知道了」全部标记已读（内容是提醒，不是待办，不需要逐条处理）。
 */
@Composable
private fun ImportantAlertCard(
    alerts: List<com.Johnny.wcx.ai.AiImportantAlert>,
    onDismiss: () -> Unit,
) {
    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        SoftCard(background = WxPalette.warningSoft()) {
            Text(
                "有 ${alerts.size} 条重要信息",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.textPrimary(),
            )
            alerts.take(3).forEach { alert ->
                Text(
                    text = "${alert.name}：${alert.text}",
                    fontSize = 12.sp,
                    color = WxPalette.textSecondary(),
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(WxPalette.primarySoft())
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text("知道了", fontSize = 12.sp, color = WxPalette.Primary)
                }
                Text(
                    text = "自动回复时识别到的截止时间、金额、地点与明确要求",
                    fontSize = 11.sp,
                    color = WxPalette.textTertiary(),
                    modifier = Modifier.weight(1f),
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun ConfigureBanner(onClick: () -> Unit = {}) {
    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        SoftCard(background = WxPalette.warningSoft(), onClick = onClick) {
            Text(
                "还没有配置 AI 接口",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.textPrimary(),
            )
            Text(
                "点这里填写接口地址、密钥与模型名。",
                fontSize = 12.sp,
                color = WxPalette.textSecondary(),
                lineHeight = 18.sp,
            )
        }
    }
}

// ── 好友列表 ────────────────────────────────────────────────────────────────

@Composable
private fun AiFriendList(
    summaries: List<AiProfileSummary>,
    onOpen: (String) -> Unit,
    onLongPress: (AiProfileSummary) -> Unit,
    onAdd: () -> Unit,
) {
    if (summaries.isEmpty()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            AiEmptyCard(
                title = "还没有好友档案",
                body = "选一个好友，让 AI 先读懂你们的聊天，之后才能给建议。",
                action = "添加好友",
                onAction = onAdd,
            )
        }
        return
    }

    // 分组顺序：A-Z 在前、`#` 在最后，与微信通讯录一致。
    val groups = remember(summaries) {
        val map = summaries.groupBy { it.pyInitial.ifBlank { AiLetterIndex.OTHER } }
        AiLetterIndex.letters.mapNotNull { letter ->
            map[letter]?.sortedBy { it.sortKey }?.let { letter to it }
        }
    }
    // 扁平化成「字母头 + 若干行」，索引条靠 headerIndexOf 定位。
    val rows = remember(groups) {
        buildList<Any> {
            groups.forEach { (letter, list) ->
                add(letter)
                addAll(list)
            }
        }
    }
    val headerIndexOf = remember(groups) {
        var cursor = 0
        buildMap {
            groups.forEach { (letter, list) ->
                put(letter, cursor)
                cursor += 1 + list.size
            }
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var activeLetter by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = CONTENT_BOTTOM_INSET),
        ) {
            items(rows, key = { row -> if (row is String) "h:$row" else "p:" + (row as AiProfileSummary).wxId }) { row ->
                if (row is String) {
                    LetterHeader(row)
                } else {
                    val summary = row as AiProfileSummary
                    FriendRow(
                        summary = summary,
                        onOpen = { onOpen(summary.wxId) },
                        onLongPress = { onLongPress(summary) },
                    )
                }
            }
        }

        LetterBar(
            letters = AiLetterIndex.letters,
            available = headerIndexOf.keys,
            active = activeLetter,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            onPick = { letter ->
                activeLetter = letter
                headerIndexOf[letter]?.let { index ->
                    scope.launch { listState.scrollToItem(index) }
                }
            },
            onRelease = { activeLetter = null },
        )

        // 拖动索引时中央显示当前字母，松手消失。
        activeLetter?.let { letter ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(WxPalette.cardBackground().copy(alpha = 0.94f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(letter, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = WxPalette.Primary)
                }
            }
        }
    }
}

@Composable
private fun LetterHeader(letter: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(WxPalette.pageBackground())
            .padding(start = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(letter, fontSize = 12.sp, color = WxPalette.textSecondary(), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FriendRow(
    summary: AiProfileSummary,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxWidth().background(WxPalette.cardBackground())) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onOpen,
                    onLongClick = onLongPress,
                )
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AiAvatar(model = summary.avatarUrl, name = displayName(summary), size = 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = displayName(summary),
                    fontSize = 15.sp,
                    color = WxPalette.textPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary.subtitle,
                    fontSize = 12.sp,
                    color = if (summary.state == AiProfileState.FAILED) WxPalette.Danger
                    else WxPalette.textSecondary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // 分割线从文字起始处开始（与头像右侧对齐）。
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 68.dp)
                .height(0.5.dp)
                .background(WxPalette.divider())
        )
    }
}

private fun displayName(summary: AiProfileSummary): String =
    summary.remark.ifBlank { summary.nickname.ifBlank { summary.wxId } }

/** 右侧字母索引条：点按或上下拖动都能跳转，没有对应分组的字母淡显。 */
@Composable
private fun LetterBar(
    letters: List<String>,
    available: Set<String>,
    active: String?,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
    onRelease: () -> Unit,
) {
    val density = LocalDensity.current
    var barHeight by remember { mutableStateOf(0) }

    Column(
        modifier = modifier
            .width(24.dp)
            .pointerInput(letters, barHeight) {
                if (barHeight <= 0) return@pointerInput
                val step = barHeight.toFloat() / letters.size
                fun pick(y: Float) {
                    val index = (y / step).toInt().coerceIn(0, letters.lastIndex)
                    onPick(letters[index])
                }
                detectVerticalDragGestures(
                    onDragStart = { offset -> pick(offset.y) },
                    onDragEnd = { onRelease() },
                    onDragCancel = { onRelease() },
                ) { change, _ -> pick(change.position.y) }
            }
            .pointerInput(letters, barHeight) {
                if (barHeight <= 0) return@pointerInput
                val step = barHeight.toFloat() / letters.size
                detectTapGestures { offset ->
                    val index = (offset.y / step).toInt().coerceIn(0, letters.lastIndex)
                    onPick(letters[index])
                    onRelease()
                }
            }
            .onSizeChanged { barHeight = it.height }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { letter ->
            val enabled = letter in available
            Text(
                text = letter,
                fontSize = 9.sp,
                color = when {
                    active == letter -> WxPalette.Primary
                    enabled -> WxPalette.textSecondary()
                    else -> WxPalette.textTertiary().copy(alpha = 0.45f)
                },
                fontWeight = if (active == letter) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(vertical = 0.5.dp),
            )
        }
    }
}

// ── 删除确认 ────────────────────────────────────────────────────────────────

@Composable
private fun DeleteProfileDialog(
    summary: AiProfileSummary,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    BackHandler { onDismiss() }
    com.Johnny.wcx.ui.content.ScrimDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = "删除档案",
    ) {
        Text(
            text = "将清空「${displayName(summary)}」的聊天目标、${summary.tagCount} 个标签、" +
                "${summary.memoryCount} 条记忆。操作不可恢复，好友本身不受影响。",
            fontSize = 13.sp,
            color = WxPalette.textSecondary(),
            lineHeight = 19.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AiSecondaryButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            Box(Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(WxPalette.Danger)
                        .clickable { onConfirm() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("删除", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

/** 轻提示：2 秒后自动消失，不拦截点击。 */
@Composable
private fun AiToast(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        kotlinx.coroutines.delay(2_000)
        onDismiss()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                // 与悬浮导航条错开：只减 CONTENT_BOTTOM_INSET 时提示会压在导航栏底下。
                .padding(
                    bottom = CONTENT_BOTTOM_INSET +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                )
                .clip(RoundedCornerShape(10.dp))
                .background(WxPalette.textPrimary().copy(alpha = 0.88f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(message, fontSize = 13.sp, color = WxPalette.pageBackground())
        }
    }
}
