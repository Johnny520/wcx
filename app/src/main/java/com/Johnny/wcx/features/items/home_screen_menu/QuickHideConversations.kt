package com.Johnny.wcx.features.items.home_screen_menu

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.activity.TransparentActivity
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.ui.WeHomeScreenPopupMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.utils.VisibilityOffIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import de.robv.android.xposed.XC_MethodHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

@Feature(
    name = "一键隐藏对话",
    categories = ["首页右上角菜单"],
    description = "在首页右上角菜单添加一键隐藏对话入口, 可全选/单选对话进行隐藏 (仅从列表移除, 保留聊天记录), 支持定时执行"
)
object QuickHideConversations : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    private const val TAG = "QuickHideConversations"
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: XC_MethodHook.MethodHookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777020, "一键隐藏对话", VisibilityOffIcon
            ) {
                TransparentActivity.launch(HostInfo.application) { showHideDialog(this) }
            }
        )
    }

    private fun showHideDialog(activity: ComponentActivity) {
        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(activity) {
            ContactsSelector(
                title = "选择要隐藏的对话",
                contacts = contacts,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast("请选择至少一个对话")
                        return@ContactsSelector
                    }
                    onDismiss()
                    showScheduleDialog(activity, selectedWxIds)
                }
            )
        }
    }

    private fun showScheduleDialog(context: android.content.Context, wxIds: Set<String>) {
        showComposeDialog(context) {
            var enableSchedule by remember { mutableStateOf(false) }
            var selectedHour by remember { mutableStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
            var selectedMinute by remember { mutableStateOf(Calendar.getInstance().get(Calendar.MINUTE) + 1) }
            var selectedDayOffset by remember { mutableStateOf(0) }

            AlertDialogContent(
                title = { Text("隐藏对话") },
                text = {
                    DefaultColumn {
                        Text(
                            "已选择 ${wxIds.size} 个对话, 隐藏后将从列表移除 (聊天记录保留), 重新收到消息时对话会再次出现.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("定时执行", modifier = Modifier.weight(1f))
                            Switch(checked = enableSchedule, onCheckedChange = { enableSchedule = it })
                        }

                        if (enableSchedule) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("延迟", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                repeat(4) { i ->
                                    val day = i + 1
                                    TextButton(
                                        onClick = { selectedDayOffset = day },
                                        modifier = Modifier.padding(horizontal = 2.dp)
                                    ) {
                                        Text(
                                            if (day == 1) "今天" else "${day}天",
                                            color = if (selectedDayOffset == day) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("时间", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                TextButton(onClick = { selectedHour = (selectedHour + 23) % 24 }) { Text("$selectedHour 时") }
                                TextButton(onClick = { selectedMinute = (selectedMinute + 59) % 60 }) { Text("$selectedMinute 分") }
                            }
                            Text(
                                "将于 ${formatScheduleTime(selectedDayOffset, selectedHour, selectedMinute)} 执行",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        if (enableSchedule) {
                            scheduleHide(wxIds, selectedDayOffset, selectedHour, selectedMinute)
                        } else {
                            hideNow(wxIds)
                        }
                    }) { Text(if (enableSchedule) "设定定时" else "立即隐藏") }
                }
            )
        }
    }

    private fun hideNow(wxIds: Set<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            showToastSuspend("正在隐藏 ${wxIds.size} 个对话...")
            var removed = 0
            withContext(Dispatchers.Main) {
                wxIds.forEach { wxId ->
                    if (WeConversationApi.hideConversation(wxId)) removed++
                }
            }
            showToastSuspend("已隐藏 $removed/${wxIds.size} 个对话")
        }
    }

    private fun scheduleHide(wxIds: Set<String>, dayOffset: Int, hour: Int, minute: Int) {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, if (dayOffset == 1) 0 else dayOffset - 1)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
        }
        val delay = cal.timeInMillis - System.currentTimeMillis()
        if (delay <= 0) {
            showToast("所选时间已过, 将立即执行")
            hideNow(wxIds)
            return
        }

        showToast("已设定定时隐藏, 将于 ${formatScheduleTime(dayOffset, hour, minute)} 执行")
        mainHandler.postDelayed({
            CoroutineScope(Dispatchers.IO).launch {
                showToastSuspend("定时任务触发, 正在隐藏 ${wxIds.size} 个对话...")
                var removed = 0
                withContext(Dispatchers.Main) {
                    wxIds.forEach { wxId ->
                        if (WeConversationApi.hideConversation(wxId)) removed++
                    }
                }
                showToastSuspend("定时隐藏完成: $removed/${wxIds.size} 个对话")
            }
        }, delay)
    }

    private fun formatScheduleTime(dayOffset: Int, hour: Int, minute: Int): String {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, if (dayOffset == 1) 0 else dayOffset - 1)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        val dayLabel = if (dayOffset == 1) "今天" else "${dayOffset}天后"
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return "$dayLabel ${sdf.format(cal.time)}"
    }
}
