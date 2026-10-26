package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import java.util.Calendar

/**
 * 主题里的两个「非素材」开关的真实实现。
 *
 * 只保留用户实际要的两项：随机背景、按时间切换主题。
 * 之前那批（圆形头像 / ActionBar 美化）已按需求下线，相关 hook 一并删除，
 * 不留「开关在页面里但没有任何实现」的假功能。
 */
object ThemeExtras {
    private const val TAG = "ThemeExtras"

    const val KEY_RANDOM_WALLPAPER = "theme_random_wallpaper"
    const val KEY_AUTO_BY_TIME = "theme_auto_by_time"
    const val KEY_AUTO_DAY_THEME = "theme_auto_day_theme"
    const val KEY_AUTO_NIGHT_THEME = "theme_auto_night_theme"

    private val switchKeys = listOf(KEY_RANDOM_WALLPAPER, KEY_AUTO_BY_TIME)

    // ── 快照 / 应用（供主题包保存与加载）─────────────────────

    fun currentSwitches(): Map<String, Boolean> =
        switchKeys.associateWith { WePrefs.getBoolOrFalse(it) }

    fun applySwitches(switches: Map<String, Boolean>) {
        if (switches.isEmpty()) return
        switches.forEach { (key, value) ->
            if (key in switchKeys) WePrefs.putBool(key, value)
        }
        if (WePrefs.getBoolOrFalse(KEY_AUTO_BY_TIME)) applyAutoThemeIfNeeded(force = true)
        WeLogger.i(TAG, "已同步主题开关 ${switches.size} 项")
    }

    fun onPrefChanged(key: String, on: Boolean) {
        when (key) {
            KEY_AUTO_BY_TIME -> {
                WeLogger.i(TAG, "按时间切换主题已${if (on) "开启" else "关闭"}")
                if (on) applyAutoThemeIfNeeded(force = true)
            }
            KEY_RANDOM_WALLPAPER -> WeLogger.i(TAG, "随机背景已${if (on) "开启" else "关闭"}")
            else -> Unit
        }
    }

    // ── 随机背景（供 ThemeStore 挑背景时调用）────────────────

    /**
     * 在给定候选里随机挑一张。开关关闭或只有一张时返回第一项。
     * 返回 null 表示「没有可用背景」。
     */
    fun pickRandom(candidates: List<java.io.File>): java.io.File? {
        if (candidates.isEmpty()) return null
        if (!WePrefs.getBoolOrFalse(KEY_RANDOM_WALLPAPER) || candidates.size == 1) return candidates.first()
        return candidates.random()
    }

    // ── 按时间切换主题 ───────────────────────────────────────

    /** 6:00–18:00 用白天主题，其余用夜间主题。未配置时返回 null。 */
    fun autoThemeForNow(): String? {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val day = WePrefs.getStringOrDef(KEY_AUTO_DAY_THEME, "")
        val night = WePrefs.getStringOrDef(KEY_AUTO_NIGHT_THEME, "")
        val target = if (hour in 6 until 18) day else night
        return target.takeIf { it.isNotBlank() }
    }

    fun setAutoThemes(dayId: String, nightId: String) {
        WePrefs.putString(KEY_AUTO_DAY_THEME, dayId)
        WePrefs.putString(KEY_AUTO_NIGHT_THEME, nightId)
    }

    /** 进入主页时调用：按时间切主题。force=false 且当前已经是对的主题时不动。 */
    fun applyAutoThemeIfNeeded(force: Boolean = false) {
        if (!WePrefs.getBoolOrFalse(KEY_AUTO_BY_TIME)) return
        val target = autoThemeForNow() ?: return
        if (!force && ThemeStore.activeThemeIdOrEmpty() == target) return
        runCatching {
            ThemeStore.applyThemeById(target)
            WeLogger.i(TAG, "按时间切换主题 -> $target")
        }.onFailure { WeLogger.w(TAG, "按时间切换主题失败：${it.message}") }
    }

    /**
     * 主界面创建完成后调用（挂点复用 ThemeStore 里的 MainTabUI#doOnCreate）。
     * 现在只剩「按时间切换主题」需要它。
     */
    fun onMainActivityCreated(@Suppress("UNUSED_PARAMETER") activity: Activity) {
        applyAutoThemeIfNeeded()
    }
}
