package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.graphics.Color
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.reflection.ClassLoaders
import com.Johnny.wcx.utils.WeLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Calendar

/**
 * 主题里那些「不属于某个 Feature」的美化项的真实实现。
 *
 * 所有开关都由 [ThemePref] 落盘，改动后在 [onPrefChanged] 里即时生效，
 * 不需要重启微信（ActionBar / 头像这类需要重建界面的除外，会记日志说明）。
 *
 * 这里不做任何「假开关」：每个 key 都有对应的实际执行代码。
 */
object ThemeExtras {
    private const val TAG = "ThemeExtras"

    const val KEY_RANDOM_WALLPAPER = "theme_random_wallpaper"
    const val KEY_AUTO_BY_TIME = "theme_auto_by_time"
    const val KEY_AUTO_DAY_THEME = "theme_auto_day_theme"
    const val KEY_AUTO_NIGHT_THEME = "theme_auto_night_theme"
    const val KEY_ROUND_AVATAR = "theme_round_avatar"
    const val KEY_HIDE_ACTIONBAR = "theme_hide_actionbar"
    const val KEY_HIDE_ACTIONBAR_SEARCH = "theme_hide_actionbar_search"
    const val KEY_HIDE_ACTIONBAR_MENU = "theme_hide_actionbar_menu"
    const val KEY_ACTIONBAR_COLOR_ENABLED = "theme_actionbar_color_enabled"
    const val KEY_ACTIONBAR_COLOR = "theme_actionbar_color"

    private val switchKeys = listOf(
        KEY_RANDOM_WALLPAPER,
        KEY_AUTO_BY_TIME,
        KEY_ROUND_AVATAR,
        KEY_HIDE_ACTIONBAR,
        KEY_HIDE_ACTIONBAR_SEARCH,
        KEY_HIDE_ACTIONBAR_MENU,
        KEY_ACTIONBAR_COLOR_ENABLED,
    )

    @Volatile
    private var installed = false

    // ── 快照 / 应用（供主题包保存与加载）─────────────────────

    fun currentSwitches(): Map<String, Boolean> =
        switchKeys.associateWith { WePrefs.getBoolOrFalse(it) }

    fun applySwitches(switches: Map<String, Boolean>) {
        if (switches.isEmpty()) return
        switches.forEach { (key, value) ->
            if (key in switchKeys) WePrefs.putBool(key, value)
        }
        onPrefChanged("", false)
        WeLogger.i(TAG, "已同步主题开关 ${switches.size} 项")
    }

    fun onPrefChanged(key: String, on: Boolean) {
        when (key) {
            KEY_HIDE_ACTIONBAR,
            KEY_HIDE_ACTIONBAR_SEARCH,
            KEY_HIDE_ACTIONBAR_MENU,
            KEY_ACTIONBAR_COLOR_ENABLED,
            -> refreshActionBar()
            KEY_ROUND_AVATAR -> WeLogger.i(
                TAG,
                "圆形头像已${if (on) "开启" else "关闭"}，重新进入聊天界面后生效",
            )
            KEY_RANDOM_WALLPAPER -> WeLogger.i(TAG, "随机聊天背景已${if (on) "开启" else "关闭"}")
            KEY_AUTO_BY_TIME -> {
                WeLogger.i(TAG, "按时间切换主题已${if (on) "开启" else "关闭"}")
                if (on) applyAutoThemeIfNeeded(force = true)
            }
            else -> {
                // 批量同步
                refreshActionBar()
            }
        }
    }

    // ── 随机背景（供 ThemeStore 挑选背景时调用）──────────────

    /**
     * 在给定候选里随机挑一张。开关关闭或只有一张时返回候选本身的第一项。
     * 返回 null 表示「不替换，用微信原生」。
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

    // ── Hook 安装 ───────────────────────────────────────────

    fun install() {
        if (installed) return
        installed = true
        installRoundAvatar()
    }

    /**
     * 主界面创建完成后调用（挂点复用 ThemeStore 里的 MainTabUI#doOnCreate，不额外依赖混淆名）：
     * 应用 ActionBar 美化 + 按时间切换主题。
     */
    fun onMainActivityCreated(activity: Activity) {
        activity.window?.decorView?.post { applyActionBar(activity) }
        activity.window?.decorView?.post { applyAutoThemeIfNeeded() }
    }

    /**
     * 圆形头像：微信 8.0.79 里聊天头像控件是
     * com.tencent.mm.ui.chatting.view.ChattingAvatarImageView（RoundedCornerImageView 在 8.0.79 已不存在）。
     * 做法是给实例挂 OnLayoutChangeListener，按当前开关把它裁成圆形 outline，
     * 关掉时恢复成微信自己的绘制。
     */
    private fun installRoundAvatar() {
        val candidates = listOf(
            "com.tencent.mm.ui.chatting.view.ChattingAvatarImageView",
            "com.tencent.kinda.framework.widget.base.MMKAvatarImageView",
        )
        var hooked = 0
        candidates.forEach { className ->
            runCatching {
                val clazz = XposedHelpers.findClass(className, ClassLoaders.HOST)
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? ImageView ?: return
                        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            if (!WePrefs.getBoolOrFalse(KEY_ROUND_AVATAR)) {
                                if (v.clipToOutline) {
                                    v.clipToOutline = false
                                    v.invalidateOutline()
                                }
                                return@addOnLayoutChangeListener
                            }
                            if (v.outlineProvider !== CircleOutline) v.outlineProvider = CircleOutline
                            if (!v.clipToOutline) {
                                v.clipToOutline = true
                                v.invalidateOutline()
                            }
                        }
                    }
                })
                hooked++
            }.onFailure { WeLogger.w(TAG, "圆形头像 hook $className 失败：${it.message}") }
        }
        if (hooked == 0) {
            WeLogger.w(TAG, "圆形头像：未找到任何头像控件，该功能在当前微信版本不可用")
        } else {
            WeLogger.i(TAG, "圆形头像 hook 已安装（命中 $hooked 个控件）")
        }
    }

    /** 圆形 outline 提供者（单例，避免每个头像都 new 一个）。 */
    private object CircleOutline : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    @Volatile
    private var lastActivity: Activity? = null

    private fun refreshActionBar() {
        val activity = lastActivity ?: return
        activity.runOnUiThread { applyActionBar(activity) }
    }

    /** 找到 MainTabUI 里的 ActionBarContainer 并按开关处理。 */
    private fun applyActionBar(activity: Activity) {
        lastActivity = activity
        val container = findActionBarContainer(activity.window?.decorView) ?: run {
            WeLogger.w(TAG, "未找到 ActionBarContainer，跳过 ActionBar 美化")
            return
        }
        val hideAll = WePrefs.getBoolOrFalse(KEY_HIDE_ACTIONBAR)
        container.visibility = if (hideAll) View.GONE else View.VISIBLE

        // 搜索 / + 菜单：ActionBar 右侧的动作按钮，按从右往左的顺序隐藏
        val actions = collectActionViews(container)
        if (actions.size >= 2) {
            actions[actions.size - 1].visibility =
                if (WePrefs.getBoolOrFalse(KEY_HIDE_ACTIONBAR_MENU)) View.GONE else View.VISIBLE
            actions[actions.size - 2].visibility =
                if (WePrefs.getBoolOrFalse(KEY_HIDE_ACTIONBAR_SEARCH)) View.GONE else View.VISIBLE
        }

        if (WePrefs.getBoolOrFalse(KEY_ACTIONBAR_COLOR_ENABLED)) {
            val color = WePrefs.getIntOrDef(KEY_ACTIONBAR_COLOR, 0xFF1E1E1E.toInt())
            container.setBackgroundColor(color)
        }
    }

    private fun collectActionViews(root: ViewGroup): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (v is ImageView && v.visibility != View.GONE) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun findActionBarContainer(v: View?): ViewGroup? {
        if (v == null) return null
        if (v is ViewGroup && v.javaClass.name == "androidx.appcompat.widget.ActionBarContainer") return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findActionBarContainer(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    // ── 颜色工具 ───────────────────────────────────────────

    fun parseColor(hex: String?): Int? = runCatching {
        if (hex.isNullOrBlank()) null else Color.parseColor(hex)
    }.getOrNull()

    fun colorHex(color: Int): String = String.format("#%08X", color)
}
