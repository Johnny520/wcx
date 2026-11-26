package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.api.core.WeCurrentConversationApi
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.utils.ChatInfoIcon
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Summarize

/**
 * AI 工作台的入口：**长按任意一条消息** → 微信原生长按菜单里多一项「AI 助手」。
 *
 * ## 为什么不用双击 / 长按输入框
 *
 * - 双击和微信自己的手势打架（用户在聊天页双击多半是想选词、放大图片）；
 * - 聊天输入框长按本来就是「转文字 / 语音」，抢过来会直接影响打字。
 *
 * 长按消息用的是微信原生菜单，复制、转发、引用都在同一份菜单里，我们只往里加一项，
 * 不改变任何原生行为 —— 点「AI 助手」才打开工作台。
 *
 * 文字、图片、语音、视频、小程序都支持：`isSupported` 对所有消息类型返回 true。
 */
@Feature(
    name = "AI 助手入口",
    categories = ["AI 助手"],
    description = "长按任意消息，在菜单里打开 AI 工作台（总结 / 待办 / 回复 / 群总结 / 生成分享图）",
)
object AiWorkbenchMenuEntry : ApiFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "AiWorkbenchMenu"

    private const val MENU_ID = 777900

    override fun onEnable() {
        runCatching { WeChatMessageContextMenuApi.addProvider(this) }
            .onFailure { WeLogger.e(TAG, "注册 AI 助手菜单项失败", it) }
    }

    override fun onDisable() {
        runCatching { WeChatMessageContextMenuApi.removeProvider(this) }
            .onFailure { WeLogger.w(TAG, "注销 AI 助手菜单项失败", it) }
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            MENU_ID,
            "AI 助手",
            ChatInfoIcon,
            MaterialSymbols.Outlined.Summarize,
            // 总开关关着就整项不出现；任何消息类型都能进工作台。
            { _ -> AiConfig.isMasterEnabled },
            multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
        ) { _, chattingContext, _ ->
            val activity = runCatching { chattingContext.activity }.getOrNull()
            if (activity == null) {
                showToast("没拿到聊天界面，稍后再试")
                return@MenuItem
            }
            val talker = runCatching { WeCurrentConversationApi.value }.getOrDefault("")
            if (talker.isBlank()) {
                showToast("没识别到当前聊天")
                return@MenuItem
            }
            // 会话名要读库，放到后台算完再弹面板。
            Thread({
                val title = runCatching { AiDigestSource.displayName(talker) }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: talker
                AiWorkbench.show(activity, talker, title, talker.isGroupChatWxId)
            }, "WCX-AiWorkbenchOpen").start()
        },
    )
}
