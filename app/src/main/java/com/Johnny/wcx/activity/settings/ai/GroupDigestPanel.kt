package com.Johnny.wcx.activity.settings.ai

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.ai.group.GroupDigestConfig
import com.Johnny.wcx.ai.group.GroupDigestStore
import com.Johnny.wcx.ai.group.GroupSummary
import com.Johnny.wcx.ui.style.WxPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 侧边栏面板里的「群总结」内容。
 *
 * 壳（遮罩 / 300dp / 滑入 / 生命周期）来自 `HomeSidePanelFeature.buildPanel`，
 * 这里只画内容：群卡片列表 + 点进去的历史。
 *
 * 打开某个群的历史即把它标记已读 —— 用户已经看见内容了，入口红点该减。
 */
@Composable
fun GroupDigestPanel(onClose: () -> Unit) {
    var groups by remember { mutableStateOf<List<GroupSummary>>(emptyList()) }
    var openId by remember { mutableStateOf<String?>(null) }
    var openConfig by remember { mutableStateOf<GroupDigestConfig?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { groups = GroupDigestStore.loadIndex().groups }
    }

    LaunchedEffect(openId) {
        val id = openId ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            GroupDigestStore.markRead(id)
            openConfig = GroupDigestStore.readConfig(id)
            groups = GroupDigestStore.loadIndex().groups
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(WxPalette.pageBackground())
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (openId != null) {
                Text(
                    text = "‹ 返回",
                    fontSize = 14.sp,
                    color = WxPalette.Primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            openId = null
                            openConfig = null
                        }
                        .padding(8.dp),
                )
            } else {
                Text(
                    text = "群总结",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = WxPalette.textPrimary(),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "关闭",
                fontSize = 14.sp,
                color = WxPalette.textSecondary(),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClose() }
                    .padding(8.dp),
            )
        }

        if (openId == null) {
            if (groups.isEmpty()) {
                Text(
                    text = "还没有群总结。\n在 AI → 群组里添加群。",
                    fontSize = 13.sp,
                    color = WxPalette.textSecondary(),
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    groups.forEach { group ->
                        PanelGroupRow(group = group, onClick = { openId = group.groupId })
                    }
                }
            }
        } else {
            HistoryPane(config = openConfig)
        }
    }
}

@Composable
private fun PanelGroupRow(group: GroupSummary, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
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
                    if (group.unread > 0) UnreadBadge(group.unread)
                }
                Text(
                    text = group.lastHeadline.ifBlank { statusLine(group) },
                    fontSize = 12.sp,
                    color = WxPalette.textSecondary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 68.dp)
                .height(0.5.dp)
                .background(WxPalette.divider()),
        )
    }
}

@Composable
private fun HistoryPane(config: GroupDigestConfig?) {
    if (config == null) {
        Text(
            text = "载入中…",
            fontSize = 13.sp,
            color = WxPalette.textSecondary(),
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    if (config.digests.isEmpty()) {
        Text(
            text = "还没有摘要。",
            fontSize = 13.sp,
            color = WxPalette.textSecondary(),
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        config.digests.asReversed().forEach { digest ->
            Column(Modifier.padding(vertical = 10.dp)) {
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
                }
                Text(
                    text = digest.headline,
                    fontSize = 14.sp,
                    color = WxPalette.textPrimary(),
                    modifier = Modifier.padding(top = 3.dp),
                )
                digest.points.forEach { point ->
                    Text(
                        text = "· $point",
                        fontSize = 13.sp,
                        color = WxPalette.textSecondary(),
                        lineHeight = 19.sp,
                    )
                }
                if (digest.todos.isNotEmpty()) {
                    Text(
                        text = "待办",
                        fontSize = 12.sp,
                        color = WxPalette.textSecondary(),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    digest.todos.forEach { todo ->
                        Text(
                            text = "· $todo",
                            fontSize = 13.sp,
                            color = WxPalette.textSecondary(),
                            lineHeight = 19.sp,
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .height(0.5.dp)
                        .background(WxPalette.divider()),
                )
            }
        }
    }
}
