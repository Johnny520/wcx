package com.Johnny.wcx.features.items.chat

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Edit
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.utils.EditIcon
import com.Johnny.wcx.utils.android.getSystemService
import com.Johnny.wcx.utils.now
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@Feature(name = "一键撤回并重新编辑", categories = ["聊天"], description = "向消息长按菜单添加菜单项, 可快捷撤回消息并将文本内容加入输入框")
object QuickRevokeAndEdit : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    fun isSupported(msgInfo: MessageInfo): Boolean {
        return msgInfo.type?.isText == true && msgInfo.isSelfSender && now() - Instant.fromEpochMilliseconds(msgInfo.createTime) <= 2.minutes
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777016, "编辑", EditIcon, MaterialSymbols.Outlined.Edit,
                isSupported = { isSupported(it) },
                // revokes then loads one message's text into the input box; single-message only
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported
            ) { view, _, msgInfo ->
                quickRevokeAndEdit(view.context, msgInfo)
            }
        )
    }

    fun quickRevokeAndEdit(context: Context, msgInfo: MessageInfo) {
        val chatFooter = WeCurrentConversationApi.chatFooter ?: return
        WeMessageApi.revokeMsg(msgInfo)
        if (msgInfo.type == MessageType.QUOTE) {
            chatFooter.lastText = msgInfo.quoteMsgActualContent ?: ""
            WeMessageApi.setReferringMessage(
                chatFooter,
                WeMessageApi.getMsgInfoInstanceByMsgSvrId(msgInfo.toQuoteMessage()!!.svrid, msgInfo.talker)
            )
        } else {
            chatFooter.lastText = msgInfo.actualContent
        }

        chatFooter.setMode(1)
        val toSendEt = chatFooter.reflekt().invokeMethod("getToSendEt")!!

        val etView = toSendEt.reflekt().firstMethod {
            returnType = View::class
        }.invoke()!! as View

        etView.requestFocus()
        val im = context.getSystemService<InputMethodManager>()
        etView.post {
            im.showSoftInput(etView, 0)
        }
    }
}
