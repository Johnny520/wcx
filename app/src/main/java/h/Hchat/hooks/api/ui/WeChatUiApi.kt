package h.Hchat.hooks.api.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.TextUtils

/**
 * 微信 UI 跳转 API。
 */
class WeChatUiApi(
    private val hostContext: Context?,
    private val notifyApi: WeChatNotifyApi?,
    private val logger: Logger?
) {
    fun interface Logger {
        fun log(message: String)
    }

    val isAvailable: Boolean
        get() = hostContext != null

    fun openChat(talker: String?): Boolean {
        return notifyApi != null && notifyApi.openChat(talker)
    }

    fun openContactProfile(wxId: String?): Boolean {
        if (TextUtils.isEmpty(wxId)) return false
        val intent = Intent()
        intent.component = ComponentName(packageName(), "com.tencent.mm.plugin.profile.ui.ContactInfoUI")
        intent.putExtra("Contact_User", wxId)
        intent.putExtra("Contact_Scene", 3)
        return start(intent)
    }

    fun openChatroomInfo(chatroomId: String?): Boolean {
        if (TextUtils.isEmpty(chatroomId)) return false
        val intent = Intent()
        intent.component = ComponentName(packageName(), "com.tencent.mm.chatroom.ui.ChatroomInfoUI")
        intent.putExtra("Chat_User", chatroomId)
        intent.putExtra("RoomInfo_Id", chatroomId)
        return start(intent)
    }

    fun openWeChatActivity(className: String?): Boolean {
        if (TextUtils.isEmpty(className)) return false
        val intent = Intent()
        intent.component = ComponentName(packageName(), className ?: return false)
        return start(intent)
    }

    fun buildChatIntent(talker: String?): Intent? {
        val intents = notifyApi?.buildChatOpenIntents(talker)
        return if (intents != null && intents.isNotEmpty()) intents[intents.size - 1] else null
    }

    private fun start(intent: Intent?): Boolean {
        if (hostContext == null || intent == null) return false
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            hostContext.startActivity(intent)
            true
        } catch (e: Throwable) {
            log("打开UI失败: ${e.message}")
            false
        }
    }

    private fun packageName(): String = hostContext?.packageName ?: "com.tencent.mm"

    private fun log(message: String) {
        logger?.log("[WeChatUiApi] $message")
    }
}
