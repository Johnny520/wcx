package com.Johnny.wcx.features.items.contacts

import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.ui.WeConversationContextMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.chat.ConversationAggregation
import com.Johnny.wcx.ui.utils.CameraIcon
import com.Johnny.wcx.utils.strings.isGroupChatWxId

@Feature(name = "快捷打开朋友圈", categories = ["联系人与群组"], description = "在首页对话列表长按菜单添加菜单项, 可点击一键跳转到朋友圈")
object QuickOpenMoments : SwitchFeature(), WeConversationContextMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeConversationContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeConversationContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> {
        return listOf(
            WeConversationContextMenuApi.MenuItem(
                id = 777018,
                text = "朋友圈",
                drawable = CameraIcon,
                shouldShow = { context, _ ->
                    val talker = context.talker
                    talker.isNotEmpty() &&
                            !talker.isGroupChatWxId &&
                            !talker.startsWith("gh_") &&
                            !talker.endsWith("@app") &&
                            !talker.startsWith(ConversationAggregation.FOLDER_PREFIX)
                },
            ) { context ->
                WeApi.openMoments(context.activity, context.talker)
            }
        )
    }
}
