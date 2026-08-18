package com.Johnny.wcx.features.items.chat_input_bar_menu

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Send_time_extension
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.ui.WeChatInputBarMenuApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.android.showToast

@Feature(
    name = "发送卡片消息",
    categories = ["聊天"],
    description = "在聊天输入栏长按菜单中添加「发送卡片消息」功能"
)
object SendCardMessage : SwitchFeature() {

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "send_card_message",
                icon = MaterialSymbols.Outlined.Send_time_extension,
                label = "发送卡片消息",
                onClick = { _, chatFooter ->
                    val currentConv = WeCurrentConversationApi.value
                    val content = chatFooter.lastText

                    if (content.isEmpty()) {
                        showToast("输入内容为空!")
                        return@ActionItem
                    }

                    val isSuccess = WeMessageApi.sendXmlAppMsg(currentConv, content)
                    if (!isSuccess) {
                        showToast("发送卡片消息失败, 请检查格式")
                        return@ActionItem
                    }

                    chatFooter.lastText = ""
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
