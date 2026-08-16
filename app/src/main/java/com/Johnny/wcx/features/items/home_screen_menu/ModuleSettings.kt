package com.Johnny.wcx.features.items.home_screen_menu

import com.tencent.mm.ui.LauncherUI
import de.robv.android.xposed.XC_MethodHook
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.features.api.ui.WeHomeScreenPopupMenuApi
import com.Johnny.wcx.features.api.ui.WeSettingsInjector
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.utils.ExtensionIcon

@Feature(name = "模块设置", categories = ["首页右上角菜单"], description = "在首页右上角菜单添加「WCX」选项")
object ModuleSettings : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: XC_MethodHook.MethodHookParam): List<WeHomeScreenPopupMenuApi.MenuItem> =
        listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                0, BuildConfig.TAG, ExtensionIcon
            ) { WeSettingsInjector.openSettingsDialog(LauncherUI.getInstance()!!) }
        )
}
