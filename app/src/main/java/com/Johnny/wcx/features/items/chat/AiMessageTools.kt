package com.Johnny.wcx.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Edit_note
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.ui.utils.ChatInfoIcon
import com.Johnny.wcx.ui.utils.showComposeDialog

/**
 * AI 实验室：消息长按菜单里的工具。
 *
 * 消息长按菜单是「针对这一条」最自然的入口 —— 用户已经选中了它，
 * 不需要再描述是哪一条。目前提供改写（自己发的文字消息）。
 */
@Feature(
    name = "AI 改写得体",
    categories = ["聊天"],
    description = "长按自己发过的文字消息，让 AI 改写成正式 / 简短 / 客气三种版本（不改原消息）",
)
object AiMessagePolish : ApiFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "AiMessagePolish"

    private const val MENU_ID = 777201

    private const val SYSTEM_PROMPT =
        "你是中文润色助手：保持原意，只输出改写后的句子，不解释、不加引号。"

    override fun onEnable() {
        runCatching { WeChatMessageContextMenuApi.addProvider(this) }
            .onFailure { com.Johnny.wcx.utils.WeLogger.e(TAG, "注册消息菜单项失败", it) }
    }

    override fun onDisable() {
        runCatching { WeChatMessageContextMenuApi.removeProvider(this) }
            .onFailure { com.Johnny.wcx.utils.WeLogger.e(TAG, "注销消息菜单项失败", it) }
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            MENU_ID,
            "AI 改写这条",
            ChatInfoIcon,
            MaterialSymbols.Outlined.Edit_note,
            // 只对自己发的文字消息有意义：别人的话不该由我改写，非文本也没有可改的内容。
            { message -> message.isSend != 0 && message.type?.isText == true && message.actualContent.isNotBlank() },
            multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
        ) { view, _, message ->
            val original = message.actualContent.trim()
            showComposeDialog(view.context) {
                AiRunDialog(
                    title = "AI 改写",
                    loadingText = "正在改写…",
                    onDismiss = { onDismiss() },
                ) { _ ->
                    TranslateSupport.chatCompletion(
                        SYSTEM_PROMPT,
                        "原句：$original\n\n" +
                                "请给出 3 个改写版本，编号 1./2./3.，每条不超过原句 1.5 倍长度：\n" +
                                "1) 更正式；2) 更简短；3) 更客气。",
                    )
                }
            }
        },
    )
}
