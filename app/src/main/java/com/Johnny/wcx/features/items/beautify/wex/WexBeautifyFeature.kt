package com.Johnny.wcx.features.items.beautify.wex

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.beautify.wex.feature.WexBottomBarFeature
import com.Johnny.wcx.features.items.beautify.wex.feature.WexHomeCardsFeature
import com.Johnny.wcx.features.items.beautify.wex.feature.WexTopBarFeature
import com.Johnny.wcx.features.items.beautify.wex.feature.WexMusicFeature
import com.Johnny.wcx.features.items.beautify.wex.ui.WexSettingsScreen
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger

/**
 * Wex 美化功能主入口 — 移植自 https://github.com/Ql1121/Wex
 *
 * 功能：
 * - 悬浮圆角底栏
 * - 顶栏美化（搜索框、头像昵称、标题自定义）
 * - 首页三卡（日历、图片、音乐）
 * - 音乐播放器
 * - 悬浮歌词
 *
 * 使用独立 SharedPreferences，不与 WCX 其他功能配置混用。
 * 关闭总开关后，全部 Wex Hook 不执行任何代码。
 */
@SuppressLint("SetTextI18n")
@Feature(
    name = "Wex美化",
    categories = ["界面美化"],
    description = "移植自Wex开源项目的美化功能：底栏美化、首页三卡、音乐播放器、顶栏美化"
)
object WexBeautifyFeature : ClickableFeature() {

    private const val TAG = "WexBeautify"
    private const val PREFS_NAME = "wex_beautify_prefs"

    /** 每日一图默认API地址 */
    const val DEFAULT_IMAGE_API = "https://api.03c3.cn/api/zb"

    /** 独立 SharedPreferences，不与 WCX 其他功能配置混用 */
    @Volatile
    lateinit var wexPrefs: SharedPreferences
        private set

    private var context: Context? = null

    // ==================== 开关状态 ====================

    var masterEnabled: Boolean
        get() = wexPrefs.getBoolean("wex_master_enabled", false)
        set(value) = wexPrefs.edit().putBoolean("wex_master_enabled", value).apply()

    var bottomBarEnabled: Boolean
        get() = wexPrefs.getBoolean("bottom_bar_enabled", true)
        set(value) = wexPrefs.edit().putBoolean("bottom_bar_enabled", value).apply()

    var topSearchBarEnabled: Boolean
        get() = wexPrefs.getBoolean("top_search_bar", true)
        set(value) = wexPrefs.edit().putBoolean("top_search_bar", value).apply()

    var topProfileEnabled: Boolean
        get() = wexPrefs.getBoolean("top_profile_enabled", true)
        set(value) = wexPrefs.edit().putBoolean("top_profile_enabled", value).apply()

    var topTitle: String
        get() = wexPrefs.getString("top_title", "") ?: ""
        set(value) = wexPrefs.edit().putString("top_title", value).apply()

    var topNickname: String
        get() = wexPrefs.getString("top_nickname", "") ?: ""
        set(value) = wexPrefs.edit().putString("top_nickname", value).apply()

    var topStatus: String
        get() = wexPrefs.getString("top_status", "") ?: ""
        set(value) = wexPrefs.edit().putString("top_status", value).apply()

    var topAvatarPath: String
        get() = wexPrefs.getString("top_avatar_path", "") ?: ""
        set(value) = wexPrefs.edit().putString("top_avatar_path", value).apply()

    var topSearchHint: String
        get() = wexPrefs.getString("top_search_hint", "") ?: ""
        set(value) = wexPrefs.edit().putString("top_search_hint", value).apply()

    var topDotColor: String
        get() = wexPrefs.getString("top_dot_color", "green") ?: "green"
        set(value) = wexPrefs.edit().putString("top_dot_color", value).apply()

    var homeCalendarCardEnabled: Boolean
        get() = wexPrefs.getBoolean("home_calendar_card", true)
        set(value) = wexPrefs.edit().putBoolean("home_calendar_card", value).apply()

    var homeImageCardEnabled: Boolean
        get() = wexPrefs.getBoolean("home_image_card", true)
        set(value) = wexPrefs.edit().putBoolean("home_image_card", value).apply()

    var homeMusicCardEnabled: Boolean
        get() = wexPrefs.getBoolean("home_music_card", true)
        set(value) = wexPrefs.edit().putBoolean("home_music_card", value).apply()

    var musicPlayerEnabled: Boolean
        get() = wexPrefs.getBoolean("music_player_enabled", true)
        set(value) = wexPrefs.edit().putBoolean("music_player_enabled", value).apply()

    var floatLyricEnabled: Boolean
        get() = wexPrefs.getBoolean("float_lyric_enabled", false)
        set(value) = wexPrefs.edit().putBoolean("float_lyric_enabled", value).apply()

    // 每日一图配置
    var imageApiUrl: String
        get() = wexPrefs.getString("image_api_url", DEFAULT_IMAGE_API) ?: DEFAULT_IMAGE_API
        set(value) = wexPrefs.edit().putString("image_api_url", value).apply()

    var imageApiKey: String
        get() = wexPrefs.getString("image_api_key", "") ?: ""
        set(value) = wexPrefs.edit().putString("image_api_key", value).apply()

    var imageRefreshInterval: Int
        get() = wexPrefs.getInt("image_refresh_interval", 3600)
        set(value) = wexPrefs.edit().putInt("image_refresh_interval", value).apply()

    var logEnabled: Boolean
        get() = wexPrefs.getBoolean("log_enabled", true)
        set(value) = wexPrefs.edit().putBoolean("log_enabled", value).apply()

    // ==================== 生命周期 ====================

    override fun onEnable() {
        WeLogger.i(TAG, "========== Wex美化: 已开启 ==========")
        val ctx = context ?: return
        wexPrefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        masterEnabled = true
        installHooks()
    }

    override fun onDisable() {
        WeLogger.i(TAG, "Wex美化: 已关闭，恢复微信原生样式")
        masterEnabled = false
    }

    // ==================== Hook 安装 ====================

    private fun installHooks() {
        if (!masterEnabled) return
        try {
            WexBottomBarFeature.install()
            WeLogger.d(TAG, "WexBottomBarFeature Hook 已安装")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "WexBottomBarFeature 安装失败", e)
        }

        try {
            WexTopBarFeature.install()
            WeLogger.d(TAG, "WexTopBarFeature Hook 已安装")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "WexTopBarFeature 安装失败", e)
        }

        try {
            WexHomeCardsFeature.install()
            WeLogger.d(TAG, "WexHomeCardsFeature Hook 已安装")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "WexHomeCardsFeature 安装失败", e)
        }

        try {
            WexMusicFeature.install()
            WeLogger.d(TAG, "WexMusicFeature Hook 已安装")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "WexMusicFeature 安装失败", e)
        }

        WeLogger.i(TAG, "========== Wex美化: 全部 Hook 安装完成 ==========")
    }

    // ==================== UI ====================

    override fun onClick(context: ComponentActivity) {
        this.context = context
        if (!::wexPrefs.isInitialized) {
            wexPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        showComposeDialog(context) {
            WexSettingsScreen(onDismiss = { /* dialog dismiss handled by showComposeDialog */ })
        }
    }
}