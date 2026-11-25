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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.ai.daily.DailyReport
import com.Johnny.wcx.ai.daily.DailyReportScheduler
import com.Johnny.wcx.ai.daily.DailyReportStore
import com.Johnny.wcx.ui.content.ScrimDialog
import com.Johnny.wcx.ui.style.SoftCard
import com.Johnny.wcx.ui.style.WxPalette
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 页的「总结」页：每日总结。
 *
 * 今天一份 + 历史列表；自动生成与时间也在这里调。
 * 报告写的是「今天发生了什么、还有什么没做完」，工作群进度会一并列出。
 */
@Composable
internal fun AiReportPage() {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var today by remember { mutableStateOf<DailyReport?>(null) }
    var history by remember { mutableStateOf<List<DailyReport>>(emptyList()) }
    var generating by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<DailyReport?>(null) }
    var autoEnabled by remember { mutableStateOf(DailyReportScheduler.isEnabled) }
    var hour by remember { mutableStateOf(DailyReportScheduler.hour) }

    fun reload() {
        scope.launch {
            val (t, h) = withContext(Dispatchers.IO) {
                DailyReportStore.read(DailyReportStore.todayKey()) to DailyReportStore.recent(14)
            }
            today = t
            history = h
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SoftCard(contentPadding = 16) {
            Text("今天的总结", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = WxPalette.textPrimary())
            val report = today
            if (report == null) {
                Text(
                    text = "还没有生成。生成后会写明今天发生了什么、和谁聊了什么、还有什么没做完。",
                    fontSize = 12.sp,
                    color = WxPalette.textSecondary(),
                    lineHeight = 17.sp,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (generating) WxPalette.Primary.copy(alpha = 0.35f) else WxPalette.Primary)
                        .clickable(enabled = !generating) {
                            generating = true
                            DailyReportScheduler.runNow { message ->
                                generating = false
                                reload()
                                showToast(context, message)
                            }
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (generating) "生成中…" else "生成今天的总结",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                }
            } else {
                if (report.headline.isNotBlank()) {
                    Text(report.headline, fontSize = 13.sp, color = WxPalette.textPrimary(), lineHeight = 19.sp)
                }
                Text(
                    text = "覆盖 ${report.friendCount} 位好友 · ${report.groupCount} 个群" +
                            if (report.workGroupCount > 0) " · ${report.workGroupCount} 个工作群" else "",
                    fontSize = 11.sp,
                    color = WxPalette.textTertiary(),
                )
                if (report.unfinished.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("还没做完", fontSize = 12.sp, color = WxPalette.textSecondary())
                        report.unfinished.take(5).forEach { item ->
                            Text("· $item", fontSize = 12.sp, color = WxPalette.textPrimary(), lineHeight = 17.sp)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionPill("查看全文") { detail = report }
                    ActionPill("重新生成") {
                        generating = true
                        DailyReportScheduler.runNow { message ->
                            generating = false
                            reload()
                            showToast(context, message)
                        }
                    }
                }
            }
        }

        SoftCard(contentPadding = 0) {
            AiSwitchRow(
                title = "每天自动生成",
                subtitle = "到点自动跑一次（微信被杀则下次启动补一份）",
                checked = autoEnabled,
                onCheckedChange = {
                    autoEnabled = it
                    DailyReportScheduler.setEnabled(it)
                },
                showDivider = autoEnabled,
            )
            if (autoEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("时间", fontSize = 13.sp, color = WxPalette.textPrimary())
                    Spacer(Modifier.weight(1f))
                    DailyReportScheduler.HOUR_OPTIONS.forEach { option ->
                        val selected = option == hour
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (selected) WxPalette.primarySoft() else WxPalette.insetSurface())
                                .clickable {
                                    hour = option
                                    DailyReportScheduler.setHour(option)
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                text = "${option}点",
                                fontSize = 12.sp,
                                color = if (selected) WxPalette.Primary else WxPalette.textSecondary(),
                            )
                        }
                    }
                }
            }
        }

        if (history.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("历史", fontSize = 13.sp, color = WxPalette.textPrimary())
                SoftCard(contentPadding = 0) {
                    history.forEachIndexed { index, report ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { detail = report }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(report.dateKey, fontSize = 13.sp, color = WxPalette.textPrimary())
                                Text(
                                    text = report.headline.ifBlank { "（无摘要）" },
                                    fontSize = 11.sp,
                                    color = WxPalette.textSecondary(),
                                    maxLines = 1,
                                )
                            }
                            Text("›", fontSize = 13.sp, color = WxPalette.textTertiary())
                        }
                        if (index != history.lastIndex) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp)
                                    .height(0.5.dp)
                                    .background(WxPalette.divider()),
                            ) {}
                        }
                    }
                }
            }
        }
    }

    val shown = detail
    if (shown != null) {
        ScrimDialog(
            show = true,
            onDismissRequest = { detail = null },
            title = shown.dateKey,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(shown.body, fontSize = 13.sp, color = WxPalette.textPrimary(), lineHeight = 20.sp)
                ActionPill("关闭") { detail = null }
            }
        }
    }
}

@Composable
private fun ActionPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(WxPalette.primarySoft())
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, fontSize = 12.sp, color = WxPalette.Primary)
    }
}
