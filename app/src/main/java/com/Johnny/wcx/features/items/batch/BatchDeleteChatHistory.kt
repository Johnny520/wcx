package com.Johnny.wcx.features.items.batch

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Feature(
    name = "批量删除聊天记录",
    categories = ["批量操作"],
    description = "彻底清除选中对话的聊天记录 (删除 rconversation 与 message 记录), 此操作不可逆!"
)
object BatchDeleteChatHistory : ClickableFeature() {

    private const val TAG = "BatchDeleteChatHistory"

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            ContactsSelector(
                title = "选择要删除聊天记录的对话",
                contacts = contacts,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast("请选择至少一个对话")
                        return@ContactsSelector
                    }

                    onDismiss()
                    confirmAndDelete(context, selectedWxIds)
                }
            )
        }
    }

    private fun confirmAndDelete(context: Context, wxIds: Set<String>) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("删除聊天记录") },
                text = { Text("确定要删除选中的 ${wxIds.size} 个对话的全部聊天记录吗? 此操作不可逆!") },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        deleteChatHistory(wxIds)
                    }) { Text("删除") }
                }
            )
        }
    }

    private fun deleteChatHistory(wxIds: Set<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            showToastSuspend("正在删除 ${wxIds.size} 个对话的聊天记录...")

            // Wipe the message rows first so a mid-way failure doesn't leave an empty conversation.
            val messagesDeleted = deleteMessageRows(wxIds) {
                WeLogger.e(TAG, "failed to delete messages", it)
            }

            // Delete the conversations the way WeChat's "删除该聊天" does (after confirm): remove the
            // rconversation row + sync the deletion to the server, not just hide the row. This is the
            // fix for the old behavior, which called deleteConversation (the "不显示该聊天" hide path).
            // It notifies list observers synchronously on the calling thread, so it must run on the
            // main thread (see WeConversationApi.reloadConversations).
            withContext(Dispatchers.Main) {
                wxIds.forEach { wxId -> WeConversationApi.deleteConversation(wxId) }
            }

            showToastSuspend("已删除 ${wxIds.size} 个对话的聊天记录 (共 $messagesDeleted 条消息)")
        }
    }
}
