package com.Johnny.wcx.features.items.beautify

import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger

/**
 * 主题里唯一不靠素材、靠开关的功能：随机聊天背景。
 *
 * 曾经还有「按时间切换主题」和一批 ActionBar/圆形头像项，都按需求下线了
 * （应用主题本身就要划掉微信重开，定时切换没有意义）。
 */
object ThemeExtras {
    private const val TAG = "ThemeExtras"

    /** 随机聊天背景：开启后每次进聊天界面从图池随机换一张。 */
    const val KEY_RANDOM_WALLPAPER = "theme_random_wallpaper"

    private val switchKeys = listOf(KEY_RANDOM_WALLPAPER)

    /** 主题包里保存的开关快照。 */
    fun currentSwitches(): Map<String, Boolean> =
        switchKeys.associateWith { WePrefs.getBoolOrFalse(it) }

    fun applySwitches(switches: Map<String, Boolean>) {
        if (switches.isEmpty()) return
        var changed = false
        switches.forEach { (key, value) ->
            if (key in switchKeys && WePrefs.getBoolOrFalse(key) != value) {
                WePrefs.putBool(key, value)
                changed = true
            }
        }
        if (changed) WeLogger.i(TAG, "已同步主题开关 ${switches.size} 项")
    }

    fun onPrefChanged(key: String, on: Boolean) {
        if (key == KEY_RANDOM_WALLPAPER) {
            WeLogger.i(TAG, "随机聊天背景已${if (on) "开启" else "关闭"}")
            if (!on) ThemeStore.clearWallpaperOnChattingPages()
        }
    }
}
