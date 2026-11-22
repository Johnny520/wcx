package com.Johnny.wcx.activity.settings.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.ai.group.GroupDigest
import com.Johnny.wcx.ai.group.GroupDigestConfig
import com.Johnny.wcx.ai.group.GroupDigestRunner
import com.Johnny.wcx.ai.group.GroupDigestScheduler
import com.Johnny.wcx.ai.group.GroupDigestStatus
import com.Johnny.wcx.ai.group.GroupDigestStore
import com.Johnny.wcx.ai.group.MAX_INTERVAL_MINUTES
import com.Johnny.wcx.ai.group.MIN_INTERVAL_MINUTES
import com.Johnny.wcx.ai.group.PRESET_INTERVALS
import com.Johnny.wcx.ai.group.nextRunAtOf
import com.Johnny.wcx.ui.content.ScrimDialog
import com.Johnny.wcx.ui.style.WxPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单个群的管理页：开关、频率、下次时间、立即总结、历史、删除。
 *
 * 打开即把该群未读清零 —— 用户已经看见历史了。
 */
@Composable
internal fun AiGroupDialog(
    groupId: String,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    var config by remember { mutableStateOf<GroupDigestConfig?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var summarizing by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var customOpen by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        val latest = withContext(Dispatchers.IO) { GroupDigestStore.readConfig(groupId) }
        if (latest != null) config = latest
        loaded = true
    }

    LaunchedEffect(groupId) {
        withContext(Dispatchers.IO) { GroupDigestStore.markRead(groupId) }
        reload()
        onChanged()
    }

    // 总结期间状态写在文件里，页面只能轮询；结束条件同时看本地标记与文件状态。
    LaunchedEffect(config?.lastStatus, summarizing) {
        if (!summarizing && config?.lastStatus != GroupDigestStatus.BUSY) return@LaunchedEffect
        while (true) {
            delay(800)
            val latest = withContext(Dispatchers.IO) { GroupDigestStore.readConfig(groupId) }
            if (latest != null) config = latest
            if (latest?.lastStatus != GroupDigestStatus.BUSY) {
                summarizing = false
                toast = when (latest?.lastStatus) {
                    GroupDigestStatus.EMPTY -> latest.lastError.ifBlank { "没有值得总结的内容" }
                    GroupDigestStatus.FAILED, GroupDigestStatus.PAUSED ->
                        latest.lastError.ifBlank { "总结失败" }
                    GroupDigestStatus.OK -> "总结完成"
                    GroupDigestStatus.UNCONFIGURED -> "未配置 AI 接口"
                    else -> null
                }
                onChanged()
                break
            }
        }
    }

    fun setEnabled(on: Boolean) {
        scope.launch {
            val now = System.currentTimeMillis()
            val updated = withContext(Dispatchers.IO) {
                GroupDigestStore.update(groupId) { current ->
                    if (on) {
                        current.copy(
                            enabled = true,
                            anchorAt = now,
                            // 从未跑过（0）保持 0：第一次窗口取 now - interval，开启后立刻能看到最近一段；
                            // 关闭再开启则从此刻起算，不补关闭期间。
                            lastSlotEnd = if (current.lastSlotEnd > 0) now else 0L,
                            failures = 0,
                            lastError = "",
                            lastStatus = "",
                        )
                    } else {
                        current.copy(enabled = false)
                    }
                }
            }
            if (updated != null) {
                withContext(Dispatchers.IO) {
                    if (on) GroupDigestScheduler.schedule(groupId) else GroupDigestScheduler.cancel(groupId)
                }
            }
            reload()
            onChanged()
        }
    }

    fun setInterval(minutes: Int) {
        if (minutes !in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES) return
        scope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                GroupDigestStore.update(groupId) {
                    // 改频率重锚；lastSlotEnd 保留，已覆盖的段不重总结、未覆盖的也不丢。
                    it.copy(intervalMinutes = minutes, anchorAt = now)
                }
                GroupDigestScheduler.schedule(groupId)
            }
            customOpen = false
            customText = ""
            reload()
            onChanged()
        }
    }

    fun summarize() {
        if (summarizing) return
        summarizing = true
        // 任务跑在进程级作用域里：关掉弹窗、重进管理页都不会中断它。
        if (!GroupDigestRunner.start(groupId, manual = true)) {
            summarizing = false
            toast = "正在总结"
            return
        }
        // 先把 busy 状态读回来，按钮立刻变成「正在总结…」。
        scope.launch {
            delay(300)
            reload()
            onChanged()
        }
    }

    ScrimDialog(show = true, onDismissRequest = onDismiss, title = "群总结") {
        val current = config
        if (!loaded) {
            Text("载入中…", fontSize = 13.sp, color = WxPalette.textSecondary())
            return@ScrimDialog
        }
        if (current == null) {
            Text("这个群已不存在", fontSize = 13.sp, color = WxPalette.textSecondary())
            AiSecondaryButton(text = "关闭", onClick = onDismiss)
            return@ScrimDialog
        }

        Column(
            modifier = Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 头部
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiAvatar(model = current.avatarUrl, name = current.name, size = 46)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = current.name.ifBlank { current.groupId },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = WxPalette.textPrimary(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${current.digests.size} 条历史 · ${current.digests.count { !it.read }} 条未读",
                        fontSize = 12.sp,
                        color = WxPalette.textSecondary(),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            AiSwitchRow(
                title = "自动总结",
                checked = current.enabled,
                onCheckedChange = { setEnabled(it) },
                subtitle = if (current.enabled) {
                    "下次 " + timeOf(nextRunAtOf(current.anchorAt, current.intervalMinutes, System.currentTimeMillis()))
                } else {
                    "打开后按下面的频率运行"
                },
                showDivider = false,
            )

            // 频率
            Text("频率", fontSize = 13.sp, color = WxPalette.textSecondary())
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PRESET_INTERVALS.forEach { minutes ->
                    IntervalChip(
                        label = intervalText(minutes),
                        selected = current.intervalMinutes == minutes,
                        onClick = { setInterval(minutes) },
                    )
                }
                IntervalChip(
                    label = if (current.intervalMinutes in PRESET_INTERVALS) "自定义" else intervalText(current.intervalMinutes),
                    selected = current.intervalMinutes !in PRESET_INTERVALS,
                    onClick = { customOpen = !customOpen },
                )
            }
            if (customOpen) {
                AiTextInput(
                    value = customText,
                    onValueChange = { customText = it.filter { ch -> ch.isDigit() }.take(3) },
                    placeholder = "1 ~ 168 小时",
                )
                AiPrimaryButton(
                    text = "确定",
                    enabled = customText.toIntOrNull()?.let { it in 1..168 } == true,
                    onClick = {
                        val hours = customText.toIntOrNull() ?: return@AiPrimaryButton
                        setInterval(hours * 60)
                    },
                )
            }

            // 状态
            Text(
                text = statusText(current),
                fontSize = 12.sp,
                color = if (current.lastStatus == GroupDigestStatus.FAILED ||
                    current.lastStatus == GroupDigestStatus.PAUSED
                ) {
                    WxPalette.Danger
                } else {
                    WxPalette.textSecondary()
                },
            )

            AiPrimaryButton(
                text = if (summarizing) "正在总结…" else "立即总结",
                enabled = !summarizing,
                onClick = { summarize() },
            )

            // 历史
            if (current.digests.isNotEmpty()) {
                Text("历史", fontSize = 13.sp, color = WxPalette.textSecondary())
                current.digests.asReversed().take(30).forEach { digest ->
                    DigestRow(
                        digest = digest,
                        expanded = expandedId == digest.id,
                        onClick = { expandedId = if (expandedId == digest.id) null else digest.id },
                    )
                }
                if (current.digests.size > 30) {
                    Text(
                        text = "只显示最近 30 条",
                        fontSize = 11.sp,
                        color = WxPalette.textTertiary(),
                    )
                }
            }

        }

        if (toast != null) {
            Text(
                text = toast.orEmpty(),
                fontSize = 12.sp,
                color = WxPalette.textSecondary(),
            )
        }
    }
}

@Composable
private fun IntervalChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) WxPalette.Primary else WxPalette.insetSurface())
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (selected) androidx.compose.ui.graphics.Color.White else WxPalette.textPrimary(),
        )
    }
}

@Composable
private fun DigestRow(
    digest: GroupDigest,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(WxPalette.insetSurface())
            .clickable { onClick() }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = timeOf(digest.createdAt),
                fontSize = 11.sp,
                color = WxPalette.textTertiary(),
            )
            if (digest.manual) {
                Spacer(Modifier.width(6.dp))
                Text("手动", fontSize = 10.sp, color = WxPalette.Primary)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "${digest.messageCount} 条",
                fontSize = 10.sp,
                color = WxPalette.textTertiary(),
            )
        }
        Text(
            text = digest.headline,
            fontSize = 14.sp,
            color = WxPalette.textPrimary(),
            maxLines = if (expanded) 10 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded) {
            digest.points.forEach { point ->
                Text("· $point", fontSize = 13.sp, color = WxPalette.textSecondary(), lineHeight = 19.sp)
            }
            if (digest.todos.isNotEmpty()) {
                Text("待办", fontSize = 12.sp, color = WxPalette.textSecondary(), fontWeight = FontWeight.Medium)
                digest.todos.forEach { todo ->
                    Text("· $todo", fontSize = 13.sp, color = WxPalette.textSecondary(), lineHeight = 19.sp)
                }
            }
        }
    }
}

private fun statusText(config: GroupDigestConfig): String {
    val next = timeOf(nextRunAtOf(config.anchorAt, config.intervalMinutes, System.currentTimeMillis()))
    return when {
        config.lastStatus == GroupDigestStatus.PAUSED ->
            "已暂停：${config.lastError.ifBlank { "连续失败" }}"
        config.lastStatus == GroupDigestStatus.FAILED ->
            "上次失败：${config.lastError.ifBlank { "未知原因" }}，下次 $next 重试"
        config.lastStatus == GroupDigestStatus.BUSY -> "正在总结…"
        config.lastStatus == GroupDigestStatus.UNCONFIGURED -> "未配置 AI 接口，不会请求"
        config.lastStatus == GroupDigestStatus.EMPTY ->
            (config.lastError.ifBlank { "上次没有值得总结的内容" }) + " · 下次 $next"
        else -> "下次 $next · 上次 ${timeOf(config.lastRunAt)}"
    }
}
