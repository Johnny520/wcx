package com.Johnny.wcx.activity.settings.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.ai.group.GroupDigestStatus
import com.Johnny.wcx.ai.group.GroupSummary
import com.Johnny.wcx.ai.group.nextRunAtOf
import com.Johnny.wcx.ui.style.WxPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 页「群组」Tab：群总结列表。
 *
 * 数据只读 [com.Johnny.wcx.ai.group.GroupDigestStore] 的索引缓存，页面不查库、不读单群文件。
 */
@Composable
internal fun GroupDigestList(
    groups: List<GroupSummary>,
    onOpen: (String) -> Unit,
    onLongPress: (GroupSummary) -> Unit,
) {
    if (groups.isEmpty()) {
        // 添加入口只有右上角一个，这里不再放按钮，避免同一动作出现两次。
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            AiEmptyCard(
                title = "还没有群总结",
                body = "点右上角「添加群」选一个群，每隔一段时间自动整理成一条摘要。",
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        items(groups, key = { it.groupId }) { group ->
            GroupRow(
                group = group,
                onOpen = { onOpen(group.groupId) },
                onLongPress = { onLongPress(group) },
            )
        }
    }
}

@Composable
private fun GroupRow(
    group: GroupSummary,
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
            AiAvatar(model = group.avatarUrl, name = group.name, size = 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = group.name.ifBlank { group.groupId },
                        fontSize = 15.sp,
                        color = WxPalette.textPrimary(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (group.workGroup && group.progressPercent >= 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(WxPalette.primarySoft())
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "${group.progressPercent}%",
                                fontSize = 11.sp,
                                color = WxPalette.Primary,
                            )
                        }
                    }
                    if (group.unread > 0) UnreadBadge(group.unread)
                }
                Text(
                    text = statusLine(group),
                    fontSize = 12.sp,
                    color = if (group.lastStatus == GroupDigestStatus.FAILED ||
                        group.lastStatus == GroupDigestStatus.PAUSED
                    ) {
                        WxPalette.Danger
                    } else {
                        WxPalette.textSecondary()
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
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

@Composable
internal fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(WxPalette.Danger)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            fontSize = 10.sp,
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 列表副标题：优先未读，其次下次时间；失败 / 暂停显示原因。 */
internal fun statusLine(group: GroupSummary): String {
    if (!group.enabled) {
        return if (group.lastStatus == GroupDigestStatus.PAUSED) {
            "已暂停：${group.lastError.ifBlank { "连续失败" }}"
        } else {
            "已关闭"
        }
    }
    if (group.lastStatus == GroupDigestStatus.BUSY) return "正在总结…"
    if (group.unread > 0) {
        return "${group.unread} 条未读 · 最近 ${timeOf(group.lastRunAt)}"
    }
    val next = nextRunAtOf(group.anchorAt, group.intervalMinutes, System.currentTimeMillis())
    return when (group.lastStatus) {
        GroupDigestStatus.EMPTY -> group.lastError.ifBlank { "最近没有值得总结的内容" }
        GroupDigestStatus.FAILED -> "上次失败：${group.lastError.ifBlank { "未知原因" }}"
        GroupDigestStatus.UNCONFIGURED -> "未配置 AI 接口"
        else -> "${intervalText(group.intervalMinutes)}一次 · 下次 ${timeOf(next)}"
    }
}

internal fun intervalText(minutes: Int): String = when {
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分"
}

internal fun timeOf(millis: Long): String {
    if (millis <= 0L) return "—"
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(millis))
    }.getOrDefault("—")
}
