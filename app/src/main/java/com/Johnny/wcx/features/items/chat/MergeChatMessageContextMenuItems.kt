package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(
    name = "消息长按菜单项合并展示",
    categories = ["聊天"],
    description = "开启后, 消息长按菜单只显示一个知友项, 点击后在对话框中集中展示所有模块菜单项"
)
object MergeChatMessageContextMenuItems : SwitchFeature() {

    // 具体实现在 WeChatMessageContextMenuApi：这里只把开关状态同步过去，
    // 让 API 层不反向依赖具体功能。
    override fun onEnable() {
        WeChatMessageContextMenuApi.mergeEnabled = true
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.mergeEnabled = false
    }
}
