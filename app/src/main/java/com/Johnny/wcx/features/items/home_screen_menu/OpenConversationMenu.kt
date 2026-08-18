package com.Johnny.wcx.features.items.home_screen_menu

import com.tencent.mm.ui.LauncherUI
import com.Johnny.wcx.features.api.ui.WeHomeScreenPopupMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.contacts.showOpenConversationDialog
import com.Johnny.wcx.ui.utils.ChatInfoIcon
import com.Johnny.wcx.utils.HookParam

@Feature(name = "跳转对话菜单", categories = ["首页右上角菜单"], description = "在首页右上角菜单添加「跳转对话」选项")
object OpenConversationMenu : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777025, "跳转对话", ChatInfoIcon
            ) {
                showOpenConversationDialog(LauncherUI.getInstance()!!)
            }
        )
    }
}
