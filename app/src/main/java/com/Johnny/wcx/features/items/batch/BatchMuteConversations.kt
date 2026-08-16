package com.Johnny.wcx.features.items.batch

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Feature(
    name = "批量免打扰",
    categories = ["批量操作"],
    description = "选择多个好友或群聊后, 批量开启或关闭消息免打扰"
)
object BatchMuteConversations : ClickableFeature() {

    private const val TAG = "BatchMuteConversations"

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("批量免打扰") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable {
                                onDismiss()
                                pickAndApply(context, mute = true)
                            },
                            supportingContent = { Text("选择要静音的对话") },
                            headlineContent = { Text("开启免打扰") },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                onDismiss()
                                pickAndApply(context, mute = false)
                            },
                            supportingContent = { Text("选择要取消静音的对话") },
                            headlineContent = { Text("关闭免打扰") },
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }

    private fun pickAndApply(context: Context, mute: Boolean) {
        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            ContactsSelector(
                title = if (mute) "选择要静音的对话" else "选择要取消静音的对话",
                contacts = contacts,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast("请选择至少一个对话")
                        return@ContactsSelector
                    }

                    onDismiss()
                    apply(selectedWxIds, mute)
                }
            )
        }
    }

    private fun apply(wxIds: Set<String>, mute: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            showToastSuspend("正在对 ${wxIds.size} 设置免打扰...")
            wxIds.forEach { wxId ->
                runCatching { WeConversationApi.setDnd(wxId, mute) }
                    .onFailure { WeLogger.e(TAG, "failed to set mute=$mute for $wxId", it) }
                delay(100.milliseconds)
            }
            WeConversationApi.reloadConversations()
            showToastSuspend(
                if (mute) "已对 ${wxIds.size} 个对话开启免打扰"
                else "已对 ${wxIds.size} 个对话关闭免打扰"
            )
        }
    }
}
