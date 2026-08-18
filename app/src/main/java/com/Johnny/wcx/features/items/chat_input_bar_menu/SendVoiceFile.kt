package com.Johnny.wcx.features.items.chat_input_bar_menu

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Voice_chat
import com.Johnny.wcx.features.api.ui.WeChatInputBarMenuApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.chat.panel.selectAndSendVoice

@Feature(
    name = "发送语音文件",
    categories = ["聊天"],
    description = "在聊天输入栏长按菜单中添加「发送语音文件」功能"
)
object SendVoiceFile : SwitchFeature() {

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "send_voice_file",
                icon = MaterialSymbols.Outlined.Voice_chat,
                label = "发送语音文件",
                onClick = { context, _ ->
                    selectAndSendVoice(context, WeCurrentConversationApi.value)
                }
            )
        )
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }
}
