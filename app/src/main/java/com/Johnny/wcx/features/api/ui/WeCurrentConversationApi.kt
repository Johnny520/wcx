package com.Johnny.wcx.features.api.ui

import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import java.lang.ref.WeakReference

@Feature(name = "当前聊天服务", categories = ["API"], description = "提供当前界面所在的聊天")
object WeCurrentConversationApi : ApiFeature() {

    var value: String = ""

    val chatFooter: ChatFooter?
        get() = chatFooterRef?.get()

    private var chatFooterRef: WeakReference<ChatFooter>? = null

    override fun onEnable() {
        ChatFooter::class.reflekt()
            .firstMethod {
                name = "setUserName"
            }.hookAfter {
                chatFooterRef = WeakReference(thisObject as ChatFooter)
                val conv = args[0] as? String
                if (!conv.isNullOrEmpty()) {
                    value = conv
                }
            }
    }
}
