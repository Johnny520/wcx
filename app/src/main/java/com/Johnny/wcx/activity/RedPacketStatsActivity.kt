package com.Johnny.wcx.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.activity.settings.MiuixListScaffold
import com.Johnny.wcx.features.items.payment.stats.RedPacketRecordEntity
import com.Johnny.wcx.features.items.payment.stats.RedPacketStatsManager
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.utils.theme.ModuleTheme
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card

/**
 * 抢红包金额统计详情页。
 *
 * 数据加载/清空/导出均在 IO 协程完成；页面销毁时 Compose 作用域自动取消
 * 未完成任务，不持有 Activity 长生命周期引用。
 */
@Keep
class RedPacketStatsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ModuleTheme {
                RedPacketStatsScreen()
            }
        }
    }
}

@Composable
private fun RedPacketStatsScreen() {
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableIntStateOf(0) }
    var summary by remember { mutableStateOf(RedPacketStatsManager.Summary()) }
    var records by remember { mutableStateOf(emptyList<RedPacketRecordEntity>()) }

    // refreshKey 变化时重新加载（挂起函数内部切 IO 线程，主线程仅等待结果）
    LaunchedEffect(refreshKey) {
        summary = RedPacketStatsManager.loadSummary()
        records = RedPacketStatsManager.loadRecentRecords()
    }

    MiuixListScaffold(title = "抢红包金额统计", navigationIcon = {}) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SummaryCard(summary)
                ActionButtons(
                    onClear = {
                        scope.launch {
                            val ok = RedPacketStatsManager.clearAll()
                            showToast(if (ok) "已清空全部记录" else "清空失败，请重试")
                            refreshKey++
                        }
                    },
                    onExport = {
                        scope.launch {
                            val result = RedPacketStatsManager.exportText()
                            showToast(result.message)
                        }
                    },
                )
                if (records.isEmpty()) {
                    Text(
                        text = "暂无记录",
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (records.isNotEmpty()) {
            items(records.size, key = { records[it].id }) { index ->
                RecordRow(records[index])
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: RedPacketStatsManager.Summary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatItem("累计总额", formatMoney(summary.totalAmount))
            StatItem("累计数量", "${summary.totalCount} 笔")
            StatItem("今日总额", formatMoney(summary.todayAmount))
            StatItem("今日数量", "${summary.todayCount} 笔")
            StatItem("本周总额", formatMoney(summary.weekAmount))
            StatItem("单个平均", formatMoney(summary.avgAmount))
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ActionButtons(onClear: () -> Unit, onExport: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onClear,
            modifier = Modifier.weight(1f),
        ) { Text("清空全部记录") }
        Button(
            onClick = onExport,
            modifier = Modifier.weight(1f),
        ) { Text("导出文本记录") }
    }
}

@Composable
private fun RecordRow(record: RedPacketRecordEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = record.chatName.ifBlank { "未知会话" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "¥${formatMoney(record.money)}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "发送人：${record.senderName.ifBlank { "未知" }}　${record.msgType}",
                fontSize = 13.sp,
            )
            Text(
                text = formatTime(record.timestamp),
                fontSize = 12.sp,
            )
        }
    }
}

private fun formatMoney(value: Double): String = String.format(Locale.US, "%.2f", value)

private fun formatTime(millis: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    return String.format(
        Locale.US,
        "%04d-%02d-%02d %02d:%02d:%02d",
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH),
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE),
        calendar.get(Calendar.SECOND),
    )
}
