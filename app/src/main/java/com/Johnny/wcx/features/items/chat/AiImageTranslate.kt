package com.Johnny.wcx.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Image_search
import com.Johnny.wcx.features.api.core.WeMessageImageStore
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.utils.ChatInfoIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger

/**
 * AI 实验室：图片翻译。
 *
 * 长按聊天里的图片 → 「AI 读图翻译」，把图里的文字提取出来并翻成中文；
 * 图里没有文字时改为一句话描述图片，避免点了没结果。
 *
 * 图片从本地缓存读（最多 1024 边长、JPEG 80），不额外下载；没缓存时提示先点开一次。
 */
@Feature(
    name = "AI 图片翻译",
    categories = ["聊天"],
    description = "长按聊天里的图片，提取图中的文字并翻译成中文（需要模型支持图片输入）",
)
object AiImageTranslate : ApiFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "AiImageTranslate"

    private const val MENU_ID = 777203

    private const val SYSTEM_PROMPT =
        "你是图片文字识别与翻译助手：只输出图片里真实存在的文字与译文，不猜测、不编造。"

    override fun onEnable() {
        runCatching { WeChatMessageContextMenuApi.addProvider(this) }
            .onFailure { WeLogger.e(TAG, "注册消息菜单项失败", it) }
    }

    override fun onDisable() {
        runCatching { WeChatMessageContextMenuApi.removeProvider(this) }
            .onFailure { WeLogger.e(TAG, "注销消息菜单项失败", it) }
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            MENU_ID,
            "AI 读图翻译",
            ChatInfoIcon,
            MaterialSymbols.Outlined.Image_search,
            { message -> message.type?.code == MessageType.IMAGE.code },
            multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
        ) { view, _, message ->
            val serverId = message.serverId
            showComposeDialog(view.context) {
                AiRunDialog(
                    title = "AI 读图翻译",
                    loadingText = "正在读图并识别文字…",
                    onDismiss = { onDismiss() },
                ) { _ ->
                    val jpeg = WeMessageImageStore.readImageJpeg(serverId, maxSide = 1024, quality = 80)
                        ?: return@AiRunDialog Result.failure(
                            IllegalStateException("这张图片还没存到本地：先在聊天里点开一次，再长按重试"),
                        )
                    TranslateSupport.chatCompletionWithImage(
                        SYSTEM_PROMPT,
                        "请用简体中文输出：\n" +
                                "1. 图片里出现的文字，逐行原样列出；\n" +
                                "2. 把这些文字翻译成简体中文（原文已是中文就跳过）；\n" +
                                "3. 图里没有文字时，用一句话描述图片内容。",
                        jpeg,
                    )
                }
            }
        },
    )
}
