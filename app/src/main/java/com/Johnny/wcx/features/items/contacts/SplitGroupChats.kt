package com.Johnny.wcx.features.items.contacts

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import com.tencent.mm.ui.chatting.ChattingUI
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.SingleContactSelector
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "分裂群组", categories = ["娱乐"], description = "让群聊一分为二; 在假群聊中发送的红包即为假红包")
object SplitGroupChats : ClickableFeature() {

    private const val TAG = "SplitGroupChats"

    /**
     * 记录最近一次分裂产生的假群聊 ID，用于 ChattingUI 退出时自动清理。
     * 使用 @Volatile 保证多线程可见性。
     */
    @Volatile
    private var lastFakeChatroomId: String? = null

    override fun onEnable() {
        WeLogger.i(TAG, "SplitGroupChats enabled, setting up ChattingUI cleanup hook")

        // Hook ChattingUI.onDestroy: 当假群聊界面关闭时自动清理数据库残留
        ChattingUI::class.java.getDeclaredMethod("onDestroy").let { method ->
            method.isAccessible = true
            hookBeforeOnDestroy(method) {
                val fakeId = lastFakeChatroomId
                if (fakeId != null) {
                    WeLogger.i(TAG, "ChattingUI destroyed, cleaning up fake chatroom: $fakeId")
                    cleanupFakeChatroom(fakeId)
                    lastFakeChatroomId = null
                }
            }
        }
    }

    private fun hookBeforeOnDestroy(method: java.lang.reflect.Method, callback: () -> Unit) {
        try {
            de.robv.android.xposed.XposedBridge.hookMethod(method, object : de.robv.android.xposed.XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    callback()
                }
            })
        } catch (e: Exception) {
            WeLogger.w(TAG, "failed to hook ChattingUI.onDestroy, cleanup will be deferred", e)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            SingleContactSelector(
                "分裂群组",
                WeDatabaseApi.getGroups(),
                initialSelectedWxId = null,
                onDismiss = onDismiss,
            ) {
                onDismiss()
                jumpToSplitChatroom(context, it)
            }
        }
    }

    private fun jumpToSplitChatroom(context: Context, wxId: String) {
        runCatching {
            val rawId = wxId.substringBefore("@")
            val targetSplitId = "${rawId}@@chatroom"
            WeLogger.i(TAG, "launching ChattingUI for fake chatroom: $targetSplitId (original: $wxId)")

            lastFakeChatroomId = targetSplitId

            val intent = Intent(context, ChattingUI::class.java).apply {
                putExtra("Chat_User", targetSplitId)
                putExtra("Chat_Mode", 1)
            }

            context.startActivity(intent)
        }.onFailure {
            WeLogger.e(TAG, "failed to launch split chatroom", it)
            lastFakeChatroomId = null
        }
    }

    /**
     * 清理假群聊的数据库残留，防止干扰正常红包发送等操作。
     * 从 fmessage 和 rconversation 表删除假群聊条目。
     */
    private fun cleanupFakeChatroom(fakeGroupId: String) {
        try {
            val tables = listOf(
                "message" to "talker",
                "rconversation" to "username",
                "rcontact" to "username",
                "chatroom" to "chatroomname",
                "img_flag" to "username",
            )

            for ((table, column) in tables) {
                try {
                    val rows = WeDatabaseApi.delete(table, "$column=?", arrayOf(fakeGroupId))
                    WeLogger.d(TAG, "  cleanup $table: deleted $rows row(s)")
                } catch (e: Exception) {
                    WeLogger.w(TAG, "  cleanup $table: failed", e)
                }
            }

            // 刷新会话列表，移除假群聊条目
            WeConversationApi.reloadConversations()
            WeLogger.i(TAG, "fake chatroom cleanup complete: $fakeGroupId")
        } catch (e: Exception) {
            WeLogger.e(TAG, "cleanupFakeChatroom failed", e)
        }
    }

    override val noSwitchWidget = true
}
