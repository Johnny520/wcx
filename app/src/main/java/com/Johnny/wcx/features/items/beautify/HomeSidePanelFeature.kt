package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import android.app.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.beautify.home_screen_panel.HomeSidePanel
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import de.robv.android.xposed.XC_MethodHook
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookBeforeDirectly
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.key
import com.Johnny.wcx.ui.utils.InjectedUiTheme
import com.Johnny.wcx.ui.utils.LifecycleOwnerProvider
import com.Johnny.wcx.ui.utils.setLifecycleOwner
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlinedfilled.Add
import com.composables.icons.materialsymbols.outlinedfilled.Bookmark
import com.composables.icons.materialsymbols.outlinedfilled.Camera
import com.composables.icons.materialsymbols.outlinedfilled.Check_circle
import com.composables.icons.materialsymbols.outlinedfilled.Close
import com.composables.icons.materialsymbols.outlinedfilled.Favorite
import com.composables.icons.materialsymbols.outlinedfilled.Movie
import com.composables.icons.materialsymbols.outlinedfilled.Person
import com.composables.icons.materialsymbols.outlinedfilled.Qr_code_scanner
import com.composables.icons.materialsymbols.outlinedfilled.Settings
import com.composables.icons.materialsymbols.outlinedfilled.Update
import com.composables.icons.materialsymbols.outlinedfilled.Wallet

/**
 * 微信主页侧滑侧边栏功能（XML+原生 View 实现）
 *
 * Bug Fix (v208): 完整功能扩展与修复（基于 v204 commit c6b9f80）
 *
 * v208 新增/修复清单：
 * ✅ 1. 头像区域：自动加载微信本人头像；长按头像弹配置弹窗（3模式：原生/本地/URL）
 * ✅ 2. 签名区域：长按弹配置（3模式：微信签名/手动文本/API+Key，失败兜底不空白）
 * ✅ 3. 天气卡片：长按配置 + API/Key 保存 + 超时异常兜底
 * ✅ 4. 每日一言：长按配置 + API/Key 保存 + 超时异常兜底
 * ✅ 5. 设置按钮：可点击弹出配置面板（不再只震动无反应）
 * ✅ 6. 快捷栏：固定4插槽，每个插槽可替换为任意功能；为空时回退默认
 * ✅ 全部 API 请求加超时 + 异常捕获 + 兜底占位文字
 * ✅ 配置全部持久化到 SharedPreferences (WePrefs)
 * ✅ 生命周期：ActivityLifecycleCallbacks 自动 addView/removeView
 * ✅ 所有 UI 逻辑 try-catch 包裹，异常仅记日志，绝不闪退
 */
@Feature(
    name = "微信主页侧边栏",
    categories = ["界面美化"],
    description = "在微信主页添加左侧侧滑边栏：方式一为内置面板（头像/签名/天气/每日一言/4 槽快捷），方式二为 WeKit 负一屏全家桶，两者互斥见设置"
)
object HomeSidePanelFeature : ClickableFeature() {

    override val alwaysEnabled = false
    override val noSwitchWidget = false

    // ==================== 视图引用 ====================
    @Volatile private var triggerButtonView: View? = null
    @Volatile private var panelRootView: View? = null
    @Volatile private var attachedActivity: Activity? = null

    // ==================== 全屏右滑跟手手势状态（WeKit 式：主界面任意位置右滑）====================
    @Volatile private var gestureHookInstalled = false
    private const val GESTURE_IDLE = 0
    private const val GESTURE_ARMED = 1
    private const val GESTURE_DRAGGING = 2
    @Volatile private var gestureState = GESTURE_IDLE
    @Volatile private var gestureDownX = 0f
    @Volatile private var gestureDownY = 0f

    // 入口按钮可见性轮询：微信 Tab 切换不产生可 hook 的触摸事件（日志确认无 GEST DOWN home=false），
    // 仅靠 ACTION_DOWN 时的 updateVisibility 无法及时隐藏「我」Tab 等非首页上的入口，因此定时对账。
    @Volatile private var visibilityPollerRunning = false
    private var visibilityPollerHandler: Handler? = null
    private var lastPollerHome = false

    // ==================== 左边缘右滑触摸条状态 ====================
    @Volatile private var edgeZoneStripView: View? = null
    @Volatile private var zoneDownX = 0f
    @Volatile private var zoneDownY = 0f
    @Volatile private var zoneSwipeTriggered = false

    // ==================== ActivityLifecycleCallbacks ====================
    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {
            if (!masterEnabled) return
            try {
                if (activity.javaClass.name == "com.tencent.mm.ui.LauncherUI") {
                    // 注册 Fragment 监听（每次进入 LauncherUI 都注册一次，幂等）
                    registerFragmentCallbacks(activity)
                    if (attachedActivity !== activity) {
                        removeAllViews()
                        attachedActivity = activity
                    }
                    updateVisibility()
                } else {
                    // 非 LauncherUI（如 ChattingUI 等子 Activity）进入前台，强制移除侧滑栏
                    if (attachedActivity === activity || panelRootView != null) {
                        removeAllViews()
                        attachedActivity = null
                    }
                }
            } catch (e: Throwable) { WeLogger.e(TAG, "onActivityResumed 异常", e) }
        }
        override fun onActivityPaused(activity: Activity) {
            try {
                if (attachedActivity === activity) {
                    removeAllViews()
                    unregisterFragmentCallbacks(activity)
                    attachedActivity = null
                }
            } catch (e: Throwable) { WeLogger.e(TAG, "onActivityPaused 异常", e) }
        }
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            try {
                if (attachedActivity === activity) {
                    removeAllViews()
                    unregisterFragmentCallbacks(activity)
                    attachedActivity = null
                }
            } catch (e: Throwable) { WeLogger.e(TAG, "onActivityDestroyed 异常", e) }
        }
    }

    // ==================== Fragment 监听（首页 Tab 切换感知）====================
    @Volatile private var fragmentCallbacksRegistered = false
    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            try { updateVisibility() } catch (e: Throwable) { WeLogger.e(TAG, "onFragmentResumed 异常", e) }
        }
        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            try { updateVisibility() } catch (e: Throwable) { WeLogger.e(TAG, "onFragmentPaused 异常", e) }
        }
    }

    private fun registerFragmentCallbacks(act: Activity) {
        try {
            if (fragmentCallbacksRegistered) return
            val fragAct = act as? FragmentActivity ?: return
            fragAct.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
            fragmentCallbacksRegistered = true
        } catch (e: Throwable) { WeLogger.e(TAG, "registerFragmentCallbacks 异常", e) }
    }

    private fun unregisterFragmentCallbacks(act: Activity) {
        try {
            if (!fragmentCallbacksRegistered) return
            val fragAct = act as? FragmentActivity ?: return
            fragAct.supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragmentCallbacks)
            fragmentCallbacksRegistered = false
        } catch (e: Throwable) { WeLogger.e(TAG, "unregisterFragmentCallbacks 异常", e) }
    }

    /**
     * 统一出口：根据当前 Activity + Fragment 判断 show/hide。
     * - 在 LauncherUI 首页 Tab：按打开方式挂载触发（全屏右滑跟手 / 左边缘右滑 / 左上角按钮，可组合）
     * - 在 LauncherUI 其它 Tab / 其它 Activity：立即 removeView 移除已打开的面板与触发视图
     */
    private fun updateVisibility() {
        val act = attachedActivity ?: return
        if (!masterEnabled) {
            removeAllViews()
            return
        }
        if (isHomePageActive(act)) {
            runCatching { diagFile("UPDATE true panel=${panelRootView != null} trigger=${triggerButtonView != null} trigParent=${triggerButtonView?.parent?.javaClass?.name}") }
            // 首页 Tab：按打开方式挂载手势/按钮（幂等）
            if (panelRootView != null) return
            val mode = migratedTriggerMode()
            if ((mode and MODE_FULL_SWIPE) != 0 && !gestureHookInstalled) attachEdgeZone(act)
            if ((mode and MODE_EDGE_STRIP) != 0 && edgeZoneStripView == null) attachEdgeZoneStrip(act)
            if ((mode and MODE_TRIGGER_BUTTON) != 0 &&
                (triggerButtonView == null || triggerButtonView!!.parent == null)
            ) attachTriggerButton(act)
        } else {
            runCatching { diagFile("UPDATE false panel=${panelRootView != null} trigger=${triggerButtonView != null} trigParent=${triggerButtonView?.parent?.javaClass?.name}") }
            // 离开首页 Tab：隐藏入口按钮/触摸条（保留挂载，轮询负责恢复），并关闭可能残留的面板
            triggerButtonView?.visibility = View.GONE
            edgeZoneStripView?.visibility = View.GONE
            if (panelRootView != null) removePanelInternal()
        }
    }
    @Volatile private var callbacksRegistered = false

    // ==================== 主开关 ====================
    private var masterEnabled by WePrefs.prefOption("${PREFS_PREFIX}master", false)

    // ==================== 打开方式（位掩码，三种可组合）====================
    // 1=全屏右滑跟手，2=左边缘右滑触摸条，4=左上角按钮；默认 7=全部开启
    private val MODE_FULL_SWIPE = 1
    private val MODE_EDGE_STRIP = 2
    private val MODE_TRIGGER_BUTTON = 4
    private var triggerMode by WePrefs.prefOption("${PREFS_PREFIX}trigger_mode", 7)
    // 左边缘触摸条宽度（dp，仅开启左边缘右滑时生效；10~80，默认 28）
    private var edgeZoneWidthDp by WePrefs.prefOption("${PREFS_PREFIX}edge_zone_width_dp", 28)
    // 侧边栏实现方式：1=方式一（本模块原版），2=方式二（WeKit 侧边栏）；默认 1
    private var sidePanelMode by WePrefs.prefOption("hsp_side_mode", 1)

    /** 旧版本编码（0=全屏,1=按钮,2=全屏+按钮）迁移到新位掩码（首次读取时写回一次）。 */
    private fun migratedTriggerMode(): Int {
        val v = triggerMode
        val m = when (v) {
            0 -> MODE_FULL_SWIPE
            1 -> MODE_TRIGGER_BUTTON
            2 -> MODE_FULL_SWIPE or MODE_TRIGGER_BUTTON
            else -> v
        }
        if (m != v) triggerMode = m
        return m
    }

    // ==================== 卡片显示开关 ====================
    var headerEnabled by WePrefs.prefOption("${PREFS_PREFIX}header_enabled", true)
    var weatherEnabled by WePrefs.prefOption("${PREFS_PREFIX}weather_enabled", true)
    var quickButtonsEnabled by WePrefs.prefOption("${PREFS_PREFIX}quick_buttons_enabled", true)
    var momentsEntryEnabled by WePrefs.prefOption("${PREFS_PREFIX}moments_entry", true)
    var videoEntryEnabled by WePrefs.prefOption("${PREFS_PREFIX}video_entry", true)
    var clearUnreadEnabled by WePrefs.prefOption("${PREFS_PREFIX}clear_unread", true)
    var wcxSettingsEnabled by WePrefs.prefOption("${PREFS_PREFIX}wcx_settings", true)
    var dailyQuoteEnabled by WePrefs.prefOption("${PREFS_PREFIX}daily_quote", true)

    // ==================== 头像配置（3 模式）====================
    // mode 0=auto_wechat / 1=local_path / 2=url
    var avatarMode by WePrefs.prefOption("${PREFS_PREFIX}avatar_mode", 0)
    var avatarLocalPath by WePrefs.prefOption("${PREFS_PREFIX}avatar_local_path", "")
    var avatarUrl by WePrefs.prefOption("${PREFS_PREFIX}avatar_url", "")

    // ==================== 签名/语录配置（3 模式）====================
    // mode 0=wechat_signature / 1=manual / 2=api
    var quoteMode by WePrefs.prefOption("${PREFS_PREFIX}quote_mode", 2)
    var quoteManual by WePrefs.prefOption("${PREFS_PREFIX}quote_manual", "")
    var useSignature by WePrefs.prefOption("${PREFS_PREFIX}use_signature", false)
    var quoteApiUrl by WePrefs.prefOption("${PREFS_PREFIX}quote_api_url", "https://v1.hitokoto.cn/")
    var quoteApiKey by WePrefs.prefOption("${PREFS_PREFIX}quote_api_key", "")
    var quoteRefreshInterval by WePrefs.prefOption("${PREFS_PREFIX}quote_refresh_interval", 3600)
    var quoteFallback by WePrefs.prefOption("${PREFS_PREFIX}quote_fallback", "每一天都是新的开始")

    // ==================== 状态行 ====================
    var onlineStatus by WePrefs.prefOption("${PREFS_PREFIX}online_status", "在线")
    var isOnline by WePrefs.prefOption("${PREFS_PREFIX}is_online", true)

    // ==================== 天气配置 ====================
    var weatherCity by WePrefs.prefOption("${PREFS_PREFIX}weather_city", "北京")
    var weatherApiUrl by WePrefs.prefOption("${PREFS_PREFIX}weather_api_url", "")  // blank = built-in Open-Meteo
    var weatherApiKey by WePrefs.prefOption("${PREFS_PREFIX}weather_api_key", "")
    var weatherRefreshInterval by WePrefs.prefOption("${PREFS_PREFIX}weather_refresh_interval", 1800)
    var weatherFallbackCity by WePrefs.prefOption("${PREFS_PREFIX}weather_fallback_city", "北京")

    // ==================== 每日一言配置 ====================
    var dailyQuoteApiUrl by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_api_url", "https://v1.hitokoto.cn/")
    var dailyQuoteApiKey by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_api_key", "")
    var dailyQuoteRefreshInterval by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_refresh_interval", 3600)
    var dailyQuoteFallback by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_fallback", "生活不止眼前的苟且，还有诗和远方")
    var dailyQuoteCacheText by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_cache_text", "")
    var dailyQuoteCacheTime by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_cache_time", 0L)
    var quoteCacheText by WePrefs.prefOption("${PREFS_PREFIX}quote_cache_text", "")
    var quoteCacheTime by WePrefs.prefOption("${PREFS_PREFIX}quote_cache_time", 0L)

    // ==================== 4 插槽固定（SlotConfig）====================
    /**
     * 4 槽固定配置数据结构。
     * - slotId: 1-4（固定编号）
     * - name/iconName/targetActivity/isCustomIntent: 功能标识
     * - 空槽回退到默认 4 个槽（扫一扫/收付款/收藏/朋友圈）
     */
    @Serializable
    data class SlotConfig(
        val slotId: Int,
        val name: String,
        val iconName: String,
        val targetActivity: String,
        val isCustomIntent: Boolean = false
    )

    /** 默认 4 个槽（用户未配置时回退） */
    private val defaultSlotConfigs: List<SlotConfig> = listOf(
        SlotConfig(1, "扫一扫", "Qr_code_scanner", "com.tencent.mm.plugin.scanner.ui.BaseScanUI"),
        SlotConfig(2, "收付款", "Wallet", "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI"),
        SlotConfig(3, "收藏", "Collections_bookmark", "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI"),
        SlotConfig(4, "朋友圈", "Favorite", "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI")
    )

    /** 自定义功能列表（来自 prefs JSON） */
    @Serializable
    data class CustomFeature(
        val id: String = UUID.randomUUID().toString(),
        val name: String = "",
        val iconName: String = "Add",
        val targetActivity: String = "",
        val isCustomIntent: Boolean = false
    )

    var customFeaturesJson by WePrefs.prefOption("${PREFS_PREFIX}custom_features_json", "")
    var quickSlotsJson by WePrefs.prefOption("${PREFS_PREFIX}quick_slots_json", "")

    /** 加载 4 插槽配置（缺失时回退默认） */
    private fun loadQuickSlots(): List<SlotConfig> {
        val json = quickSlotsJson
        if (json.isBlank()) return defaultSlotConfigs
        return try {
            val parsed = Json.decodeFromString(ListSerializer(SlotConfig.serializer()), json)
            // 保证 4 个 slot，缺失时回退
            val result = defaultSlotConfigs.toMutableList()
            for (i in 0..3) {
                if (i < parsed.size) result[i] = parsed[i].copy(slotId = i + 1)
            }
            result
        } catch (e: Exception) {
            WeLogger.e(TAG, "解析 4 槽配置失败", e)
            defaultSlotConfigs
        }
    }

    private fun saveQuickSlots(list: List<SlotConfig>) {
        quickSlotsJson = Json.encodeToString(list)
    }

    private fun loadCustomFeatures(): List<CustomFeature> {
        val json = customFeaturesJson
        if (json.isBlank()) return emptyList()
        return try {
            Json.decodeFromString(ListSerializer(CustomFeature.serializer()), json)
        } catch (e: Exception) {
            WeLogger.e(TAG, "解析自定义功能配置失败", e)
            emptyList()
        }
    }

    private fun saveCustomFeatures(list: List<CustomFeature>) {
        customFeaturesJson = Json.encodeToString(list)
    }

    private val iconPool: Map<String, String> = mapOf(
        "Qr_code_scanner" to "🔍",
        "Wallet" to "💰",
        "Collections_bookmark" to "★",
        "Favorite" to "♥",
        "Person" to "☻",
        "Add" to "+",
        "Refresh" to "↻",
        "Settings" to "⚙",
        "Cloud" to "☁",
        "Delete" to "✕",
        "Menu_open" to "✕",
        "Camera" to "◉",
        "Photo" to "▦",
        "Video_call" to "▶"
    )

    /** 微信原生跳转功能（用于 slot 选择） */
    private val wechatNativeTargets: List<Triple<String, String, String>> = listOf(
        Triple("扫一扫", "🔍", "com.tencent.mm.plugin.scanner.ui.BaseScanUI"),
        Triple("朋友圈", "♥", "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI"),
        Triple("收藏", "★", "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI"),
        Triple("钱包", "💰", "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI"),
        Triple("视频号", "▶", "com.tencent.mm.plugin.finder.ui.FinderHomeUI"),
        Triple("通讯录", "☻", "com.tencent.mm.ui.contact.ContactsUI"),
        Triple("我", "★", "com.tencent.mm.ui.MeTabUI"),
        Triple("设置", "⚙", "com.tencent.mm.ui.setting.SettingsUI"),
        Triple("搜一搜", "🔍", "com.tencent.mm.plugin.search.ui.SearchMainUI"),
        Triple("小程序", "▦", "com.tencent.mm.plugin.appbrand.ui.AppBrandMainUI")
    )

    /** 模块内置功能（用于 slot 选择） */
    private fun moduleTargets(): List<Triple<String, String, String>> {
        return listOf(
            Triple("WCX 设置", "⚙", "com.Johnny.wcx.SettingsActivity"),
            Triple("清空未读", "✓", "__clear_unread__"),
            Triple("群成员变动提醒", "☻", "__group_member__")
        )
    }

    // ==================== 数据缓存 ====================
    private var cachedWeather: WeatherData? = null
    private var lastWeatherFetchTime: Long = 0L
    private var cachedDailyQuote: String = ""
    private var lastDailyQuoteFetchTime: Long = 0L
    private var cachedWeChatSignature: String = ""
    private var lastSignatureFetchTime: Long = 0L

    data class WeatherData(
        val city: String, val temperature: String, val feelsLike: String,
        val tempHigh: String, val tempLow: String, val humidity: String,
        val windSpeed: String, val weather: String, val updateTime: String, val weatherIcon: String
    )

    // ==================== Compose 渲染状态 ====================
    private var panelRefresh by mutableStateOf(0)
    private var uiAvatar by mutableStateOf<Bitmap?>(null)
    private var uiNickname by mutableStateOf("")
    private var uiTime by mutableStateOf("")
    private var uiDate by mutableStateOf("")
    private var uiWeather by mutableStateOf<WeatherData?>(null)
    private var uiSignature by mutableStateOf("")
    private var uiDailyQuote by mutableStateOf("")

    // ==================== 生命周期入口 ====================
    override fun onEnable() {
        if (!BuildConfig.BEAUTIFY_ENABLED) {
            WeLogger.w(TAG, "侧边栏功能编译开关已关闭，跳过启用"); return
        }
        if (sidePanelMode == 2) {
            WeLogger.i(TAG, "侧边栏实现方式为方式二（WeKit 版），启用 WeKit 负一屏面板")
            HomeSidePanel.installPanel()
            return
        }
        masterEnabled = true
        registerActivityCallbacks()
    }

    override fun onDisable() {
        HomeSidePanel.uninstallPanel()
        masterEnabled = false
        gestureHookInstalled = false
        unregisterActivityCallbacks()
        removeAllViews()
        attachedActivity = null
    }

    private fun registerActivityCallbacks() {
        if (callbacksRegistered) return
        try {
            val app = currentApplication() ?: return
            app.registerActivityLifecycleCallbacks(activityCallbacks)
            callbacksRegistered = true
        } catch (e: Throwable) { WeLogger.e(TAG, "注册回调失败", e) }
    }

    private fun unregisterActivityCallbacks() {
        if (!callbacksRegistered) return
        try {
            currentApplication()?.unregisterActivityLifecycleCallbacks(activityCallbacks)
            callbacksRegistered = false
        } catch (e: Throwable) { WeLogger.e(TAG, "注销回调失败", e) }
    }

    private fun currentApplication(): Application? = try {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? Application
    } catch (_: Throwable) { null }

    /**
 * 判断当前活动 + Fragment 是否处于【微信首页会话列表】。
 * 微信主 Activity 是 LauncherUI，但内部 4 个 Tab（首页/通讯录/发现/我）都是 Fragment。
 * 因此必须同时校验 Activity 是 LauncherUI && 当前 resumed Fragment 是首页 Tab。
 * 进入其它 Tab、私聊/群聊 Activity（ChattingUI）时返回 false。
 */
fun isHomePageActive(act: Activity): Boolean = try {
    if (act.isFinishing) return false
    if (act.javaClass.name != "com.tencent.mm.ui.LauncherUI") return false
    // 微信 LauncherUI 可能不继承 androidx FragmentActivity（自研 Activity 基类），
    // 此时无法枚举 Fragment；仅凭 LauncherUI 类名放行，非 LauncherUI 的全局
    // 误触发由 handleDispatchTouch 里的类名精确检查兜底。
    val fragAct = act as? FragmentActivity
    if (fragAct == null) {
        dumpLauncherDiagnostics(act)
        dumpTopBar(act)
        // 微信 LauncherUI 非 androidx FragmentActivity（classloader 隔离），无法枚举 Fragment；
        // 「微信」Tab 用 AppCompat ActionBar（ActionBarOverlayLayout），其余 Tab（通讯录/发现/我）
        // 是 MultiTaskContainerView 容器、无 ActionBar —— 用 ActionBarOverlayLayout 可见性识别「微信」Tab。
        val isWeChatTab = runCatching {
            var found = false
            var actionBarShown = false
            val decorAttached = act.window?.decorView?.isAttachedToWindow == true
            fun scan(v: View, depth: Int) {
                if (found || depth > 16) return
                val n = v.javaClass.name
                // 微信 Tab 独有特征：主会话列表（聊天列表）。通讯录/发现/我 Tab 顶栏也有
                // ActionBar，仅靠 ActionBar 会全部误判；以会话列表屏幕可见为准。
                if (n == "com.tencent.mm.ui.conversation.ConversationListView") {
                    var onScreen = false
                    if (v.isAttachedToWindow && v.isShown) {
                        val r = android.graphics.Rect()
                        onScreen = v.getGlobalVisibleRect(r) && r.width() > 80 && r.height() > 80
                    }
                    runCatching { diagFile("CONV v=$n onScreen=$onScreen attached=${v.isAttachedToWindow} shown=${v.isShown}") }
                    if (onScreen) {
                        found = true
                        return
                    }
                }
                // 诊断：记录各列表容器类名（去重），用于确认微信会话列表的真实类名
                if (n.contains("RecyclerView") || n.contains("ListView")) {
                    runCatching {
                        if (listDiagSet.add(n)) diagFile("LIST v=$n shown=${v.isShown}")
                    }
                }
                if (!found && !decorAttached &&
                    (n == "androidx.appcompat.widget.ActionBarOverlayLayout" ||
                        n == "android.widget.ActionBarOverlayLayout")
                ) {
                    // ActionBar 仅作启动早期兜底：decor 未 attached（会话列表尚未渲染）时挂入口，
                    // attached 后一律以会话列表为准（否则通讯录/发现/我 Tab 会全部误判）。
                    if (isVisibleTree(v)) actionBarShown = true
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) scan(v.getChildAt(i), depth + 1)
            }
            scan(act.window.decorView, 0)
            found || actionBarShown
        }.getOrDefault(false)
        diagIsHome(act, null, isWeChatTab, null)
        return isWeChatTab
    }
    val current = fragAct.supportFragmentManager.fragments.find { it.isResumed && it.isVisible && !it.isHidden }
    if (current == null) {
        diagIsHome(act, null, false, null)
        return false
    }
    if (!isHomeTabClass(current.javaClass.name)) {
        diagIsHome(act, current.javaClass.name, false, null)
        return false
    }
    // 屏幕可见性校验（宽松）：ViewPager 预加载的非当前 Tab view 位于屏幕外；
    // 未完成布局（width==0）或 rect 不可用时不判定，避免误伤首页。
    val v = current.view
    if (v != null && v.isShown) {
        val r = android.graphics.Rect()
        if (v.getGlobalVisibleRect(r)) {
            val visibleArea = r.width().toLong() * r.height()
            val totalArea = v.width.toLong() * v.height
            if (totalArea > 0 && visibleArea * 10 < totalArea) {
                diagIsHome(act, current.javaClass.name, true, false)
                return false
            }
        }
    }
    diagIsHome(act, current.javaClass.name, true, true)
    true
} catch (e: Throwable) { WeLogger.e(TAG, "isHomePageActive 异常", e); false }

/** 判断 view 及其祖先链是否全部 VISIBLE（不要求 attached/已布局，排除父容器 GONE） */
private fun isVisibleTree(v: View): Boolean {
    if (v.visibility != View.VISIBLE) return false
    var p = v.parent
    while (p is View) {
        if (p.visibility != View.VISIBLE) return false
        p = p.parent
    }
    return true
}

/** 诊断：isHomePageActive 判定变化时写一次 diag.log（限频） */
@Volatile private var lastDiagHomeKey = ""
private val listDiagSet = java.util.Collections.synchronizedSet(java.util.HashSet<String>())
private fun diagIsHome(act: Activity, fragCls: String?, isHome: Boolean, visible: Boolean?) {
    val key = "${act.javaClass.name}|$fragCls|$isHome|$visible"
    if (key == lastDiagHomeKey) return
    lastDiagHomeKey = key
    runCatching {
        val f = java.io.File("/sdcard/Android/data/com.tencent.mm/WCX/diag.log")
        f.parentFile?.mkdirs()
        f.appendText(System.currentTimeMillis().toString() +
            " isHomePageActive act=${act.javaClass.name} super=${act.javaClass.superclass?.name}" +
            " frag=$fragCls isHome=$isHome visible=$visible\n")
    }
}

/** 诊断：记录 LauncherUI decor 顶层 view 结构签名（去重）。用户在 4 个 Tab 间切换时，
 *  每个 Tab 的顶栏（搜索/+ 栏等）结构不同，据此识别「微信」Tab 以控制侧边栏入口显隐。 */
private val topBarDiagSet = java.util.Collections.synchronizedSet(java.util.HashSet<String>())
private fun diagFile(msg: String) {
    runCatching {
        val f = java.io.File("/sdcard/Android/data/com.tencent.mm/WCX/diag.log")
        f.parentFile?.mkdirs()
        f.appendText(System.currentTimeMillis().toString() + " " + msg + "\n")
    }
}
private fun dumpTopBar(act: Activity) {
    if (topBarDiagSet.size > 40) return
    try {
        val decor = act.window.decorView
        val sb = StringBuilder()
        fun walk(v: View, depth: Int) {
            if (depth > 5 || sb.length > 2000) return
            val vis = if (v.visibility == View.VISIBLE) "V" else "G"
            val idName = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
            val txt = if (v is TextView) (v.text?.toString() ?: "").take(8) else ""
            sb.append(v.javaClass.simpleName).append('#').append(idName).append('#').append(vis).append('[').append(txt).append("] ")
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(decor, 0)
        val sig = sb.toString()
        if (sig.isNotBlank() && topBarDiagSet.add(sig)) {
            val f = java.io.File("/sdcard/Android/data/com.tencent.mm/WCX/diag.log")
            f.parentFile?.mkdirs()
            f.appendText(System.currentTimeMillis().toString() + " TOPBAR " + sig.take(1500) + "\n")
        }
    } catch (e: Throwable) {
        runCatching {
            val f = java.io.File("/sdcard/Android/data/com.tencent.mm/WCX/diag.log")
            f.parentFile?.mkdirs()
            f.appendText(System.currentTimeMillis().toString() + " TOPBAR ERR $e\n")
        }
    }
}

/** 诊断：LauncherUI 非 androidx FragmentActivity 时，dump 继承链与 fragment 反射信息（限频一次） */
@Volatile private var launcherDiagDone = false
private fun dumpLauncherDiagnostics(act: Activity) {
    if (launcherDiagDone) return
    launcherDiagDone = true
    runCatching {
        val f = java.io.File("/sdcard/Android/data/com.tencent.mm/WCX/diag.log")
        f.parentFile?.mkdirs()
        val append: (String) -> Unit = { s -> f.appendText(System.currentTimeMillis().toString() + " " + s + "\n") }

        val sb = StringBuilder()
        var cls: Class<*>? = act.javaClass
        while (cls != null) {
            sb.append(cls.name).append(" > ")
            cls = cls.superclass
        }
        append("LauncherUI chain: $sb")

        val supportCls = runCatching { Class.forName("android.support.v4.app.FragmentActivity") }.getOrNull()
        append("old-support-FragmentActivity present=${supportCls != null} isInstance=${supportCls?.isInstance(act)}")
        val androidxCls = runCatching { Class.forName("androidx.fragment.app.FragmentActivity") }.getOrNull()
        append("androidx-FragmentActivity isInstance=${androidxCls?.isInstance(act)}")

        // 反射尝试各种 fragment 管理器
        val candidates = listOf(
            "getSupportFragmentManager", "getFragmentManager",
            "getMMFragmentManager", "getChildFragmentManager"
        )
        for (mName in candidates) {
            runCatching {
                val m = act.javaClass.getMethod(mName)
                val fm = m.invoke(act) ?: return@runCatching
                val getFrags = fm.javaClass.methods.firstOrNull { it.name == "getFragments" }
                    ?: return@runCatching
                val frags = getFrags.invoke(fm) as? List<*> ?: return@runCatching
                val desc = frags.mapNotNull { fr ->
                    runCatching {
                        val fcls = fr?.javaClass ?: return@runCatching
                        val r = runCatching { fcls.getMethod("isResumed").invoke(fr) as Boolean }.getOrDefault(false)
                        val v = runCatching { fcls.getMethod("isVisible").invoke(fr) as Boolean }.getOrDefault(false)
                        val h = runCatching { fcls.getMethod("isHidden").invoke(fr) as Boolean }.getOrDefault(false)
                        val vw = runCatching { fcls.getMethod("getView").invoke(fr) }.getOrNull()
                        "${fcls.name}(r=$r,v=$v,h=$h,view=${vw?.javaClass?.name})"
                    }.getOrNull()
                }
                append("fragmentManager($mName) size=${frags.size}: $desc")
            }
        }
    }
}

/**
 * 首页 Tab Fragment 类名匹配（兼容多版本微信）：
 * - 微信 8.x：com.tencent.mm.ui.conversation.ConversationListFragment
 * - 微信 7.x：com.tencent.mm.ui.HomeMainUI$HomeChatFragment / HomeMainFragment
 * - 微信 6.x：com.tencent.mm.ui.HomeMainUI
 * 匹配规则：必须是首页会话列表类，且不能是通讯录/我/发现 Fragment。
 */
private fun isHomeTabClass(className: String): Boolean {
    if (className.isEmpty()) return false
    // 排除其他 Tab Fragment
    if (className.contains("Contact") || className.contains("Address") ||
        className.contains("MeTab") || className.contains("Finder") ||
        className.contains("Setting") || className.contains("DiscoverUI") ||
        className.contains("FindFragment") || className.contains("SelfInfo")) {
        return false
    }
    // 命中首页 Tab
    return className.contains("HomeMainUI") ||
           className.contains("ConversationList") ||
           className.contains("HomeChatFragment") ||
           className.contains("MainUI") && className.contains("Home") ||
           className == "com.tencent.mm.ui.LandingPageUI"
}

    // ==================== 左上角触发按钮 ====================
    private fun detectWeChatOfficialEntry(act: Activity): Int = try {
        val root = act.window?.decorView ?: return 0
        val queue = ArrayDeque<View>()
        queue.add(root)
        val offsetCandidates = mutableListOf<Int>()
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v is ViewGroup) for (i in 0 until v.childCount) v.getChildAt(i)?.let { queue.add(it) }
            val tag = v.tag
            if (tag != null && tag.toString().contains("ai", ignoreCase = true)) {
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                if (loc[0] in 1..120 && loc[1] in 40..200) offsetCandidates.add(v.width + 16)
            }
        }
        offsetCandidates.maxOrNull() ?: 0
    } catch (_: Throwable) { 0 }

    /** 找「微信」Tab 顶栏 Toolbar（AppCompat ActionBar 容器内），用于把触发按钮对齐到顶栏左缘 */
    private fun findWeChatToolbar(root: View): View? {
        if (root.javaClass.name == "androidx.appcompat.widget.Toolbar" ||
            root.javaClass.name == "android.widget.Toolbar"
        ) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val r = findWeChatToolbar(root.getChildAt(i)) ?: continue
                return r
            }
        }
        return null
    }

    private fun attachTriggerButton(act: Activity) {
        if (triggerButtonView != null) return
        val decorView = act.window?.decorView as? ViewGroup ?: return
        try {
            val d = act.resources.displayMetrics.density
            val statusBarH = getStatusBarHeight(act)
            val extraOffset = detectWeChatOfficialEntry(act)
            val triggerSizePx = (40 * d).toInt()
            val trigger = FrameLayout(act).apply {
                tag = "home_side_panel_trigger"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(AndroidColor.parseColor(if (isDarkMode(act)) "#2C2C2C" else "#FFFFFF"))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = 4f * d
                setOnClickListener { try { showPanel(act) } catch (e: Throwable) { WeLogger.e(TAG, "触发按钮异常", e) } }
            }
            val iconHost = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams((22 * d).toInt(), (16 * d).toInt(), Gravity.CENTER)
                gravity = Gravity.CENTER
            }
            repeat(3) {
                val bar = View(act).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (2 * d).toInt()).apply { topMargin = (1 * d).toInt() }
                    setBackgroundColor(AndroidColor.parseColor(if (isDarkMode(act)) "#64B5F6" else "#1976D2"))
                }
                iconHost.addView(bar)
            }
            trigger.addView(iconHost)
            // 入口按钮采用 WindowManager 悬浮窗（参考 ALink「获」入口）：直挂 Activity window，
            // 完全独立于页面容器，显隐由轮询按当前 Tab 控制，不会因共享 Toolbar 在「我」Tab 残留。
            // FLAG_NOT_TOUCH_MODAL + FLAG_NOT_FOCUSABLE：按钮外触摸穿透（不拦截右滑等页面手势）。
            val wm = act.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            var attached = false
            if (wm != null) {
                runCatching {
                    val winToken = act.window.attributes.token ?: act.window.decorView.windowToken
                    val winLp = android.view.WindowManager.LayoutParams(
                        triggerSizePx, triggerSizePx,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        android.graphics.PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = (4 * d).toInt()
                        y = statusBarH + (8 * d).toInt()
                        token = winToken
                        windowAnimations = 0
                    }
                    if (trigger.parent != null) (trigger.parent as? ViewGroup)?.removeView(trigger)
                    wm.addView(trigger, winLp)
                    attached = true
                    runCatching { diagFile("ATTACH WIN token=${winToken != null}") }
                }.onFailure { runCatching { diagFile("ATTACH WIN 失败: ${it.javaClass.simpleName} ${it.message?.take(80)}") } }
            }
            if (!attached) {
                runCatching { diagFile("ATTACH FALLBACK decor（WindowManager 挂载失败）") }
                // 回退：直接挂 decorView 左上角（与悬浮窗同位置语义）
                val lp = FrameLayout.LayoutParams(triggerSizePx, triggerSizePx).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = (16 * d).toInt() + extraOffset
                    topMargin = statusBarH + (8 * d).toInt()
                }
                if (trigger.parent != null) (trigger.parent as? ViewGroup)?.removeView(trigger)
                decorView.addView(trigger, lp)
            }
            triggerButtonView = trigger
            startVisibilityPoller(act)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "attachTriggerButton 异常", e)
            try { triggerButtonView?.let { if (it.parent != null) (it.parent as? ViewGroup)?.removeView(it) } } catch (_: Throwable) {}
            triggerButtonView = null
        }
    }

    // ==================== 入口可见性轮询（微信 Tab 才显示入口/面板） ====================
    private fun startVisibilityPoller(act: Activity) {
        visibilityPollerRunning = false
        visibilityPollerHandler?.removeCallbacksAndMessages(null)
        visibilityPollerRunning = true
        val h = Handler(android.os.Looper.getMainLooper())
        visibilityPollerHandler = h
        h.post(object : Runnable {
            override fun run() {
                if (!visibilityPollerRunning) return
                runCatching { syncLauncherUi(act) }
                h.postDelayed(this, 400L)
            }
        })
    }

    private fun stopVisibilityPoller() {
        visibilityPollerRunning = false
        visibilityPollerHandler?.removeCallbacksAndMessages(null)
        visibilityPollerHandler = null
    }

    /** 定时对账：非「微信」Tab 时隐藏入口、移除残留面板；回到「微信」Tab 时恢复入口。 */
    private fun syncLauncherUi(act: Activity) {
        val home = isHomePageActive(act)
        if (home != lastPollerHome) {
            lastPollerHome = home
            runCatching { diagFile("POLL home=$home btn=${triggerButtonView != null} panel=${panelRootView != null}") }
        }
        val btn = triggerButtonView
        if (btn != null) {
            val hidden = !home || panelRootView != null || gestureState != GESTURE_IDLE
            val target = if (hidden) View.GONE else View.VISIBLE
            if (btn.visibility != target) btn.visibility = target
        }
        // 触摸条跟随入口显隐（非「微信」Tab 隐藏，避免残留拦截区域）
        if (edgeZoneStripView != null) {
            val stripTarget = if (home) View.VISIBLE else View.GONE
            if (edgeZoneStripView!!.visibility != stripTarget) edgeZoneStripView!!.visibility = stripTarget
        }
        if (!home && panelRootView != null) {
            removePanelInternal()
        }
    }

    private fun hideTrigger() {
        val tv = triggerButtonView ?: return
        try {
            val wm = tv.context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            if (wm != null && tv.isAttachedToWindow) {
                runCatching { wm.removeView(tv) }
            }
            if (tv.parent != null) (tv.parent as? ViewGroup)?.removeView(tv)
        } catch (e: Throwable) { WeLogger.w(TAG, "移除触发按钮失败", e) }
        triggerButtonView = null
    }

    private fun removeTriggerButton() {
        hideTrigger()
    }

    // ==================== 全屏右滑跟手（WeKit 式：主界面任意位置右滑，面板跟随手指呼出）====================
    /**
     * 挂载全屏右滑跟手手势：Hook 首页 Activity 的 dispatchTouchEvent（getMethod 命中
     * LauncherUI 自身或父类最近的重写）。按下不消费（微信正常处理），判定为水平右滑后向
     * 内容发送 ACTION_CANCEL 取消微信原生手势，随后接管为侧栏跟手拖拽，完全跟随手指。
     */
    private fun attachEdgeZone(act: Activity) {
        if (gestureHookInstalled) return
        try {
            val method = act.javaClass.getMethod("dispatchTouchEvent", MotionEvent::class.java)
            registerUnhook(
                method.hookBeforeDirectly {
                    handleDispatchTouch(this)
                }
            )
            gestureHookInstalled = true
            WeLogger.i(TAG, "全屏右滑跟手手势已挂载（${act.javaClass.name}）")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "attachEdgeZone 挂载手势异常", e)
            gestureHookInstalled = false
        }
    }

    private fun removeEdgeZone() {
        // 全屏跟手手势由 Xposed Hook 实现，禁用功能时经 unhookAll() 统一移除
    }

    // ==================== 左边缘右滑触摸条（隐形 View，挂到 decorView 左缘）====================
    private fun attachEdgeZoneStrip(act: Activity) {
        if (edgeZoneStripView != null) return
        try {
            val decorView = act.window?.decorView as? ViewGroup ?: return
            val d = act.resources.displayMetrics.density
            val zone = View(act).apply {
                tag = "home_side_panel_edge_zone"
                setOnTouchListener { _, ev -> handleZoneTouch(act, ev) }
            }
            val zoneWidth = edgeZoneWidthDp.coerceIn(10, 80)
            val lp = FrameLayout.LayoutParams((zoneWidth * d).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.TOP
            }
            if (zone.parent != null) (zone.parent as? ViewGroup)?.removeView(zone)
            decorView.addView(zone, lp)
            edgeZoneStripView = zone
            WeLogger.i(TAG, "左边缘右滑触摸条已挂载（宽 ${zoneWidth}dp）")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "attachEdgeZoneStrip 异常", e)
            edgeZoneStripView = null
        }
    }

    private fun removeEdgeZoneStrip() {
        val zone = edgeZoneStripView ?: return
        try {
            if (zone.parent != null) (zone.parent as? ViewGroup)?.removeView(zone)
        } catch (e: Throwable) { WeLogger.w(TAG, "移除左边缘触摸条失败", e) }
        edgeZoneStripView = null
    }

    /** 触摸条事件：右滑超过 72dp 且基本水平 → 打开侧栏（面板平滑展开 + 收尾，与全屏右滑一致）。 */
    private fun handleZoneTouch(act: Activity, ev: MotionEvent): Boolean {
        if (!masterEnabled) return false
        if (!isHomePageActive(act)) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                zoneDownX = ev.rawX
                zoneDownY = ev.rawY
                zoneSwipeTriggered = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (zoneSwipeTriggered) return true
                val dx = ev.rawX - zoneDownX
                val dy = ev.rawY - zoneDownY
                val thresholdPx = (72 * act.resources.displayMetrics.density).toFloat()
                if (dx >= thresholdPx && Math.abs(dy) <= dx * 0.8f) {
                    zoneSwipeTriggered = true
                    try { openPanelFromEdgeZone(act) } catch (e: Throwable) { WeLogger.e(TAG, "左边缘手势打开面板异常", e) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> zoneSwipeTriggered = true
        }
        return true
    }

    /** 左边缘滑动触发：与全屏右滑一致地把面板展开并收尾加载。 */
    private fun openPanelFromEdgeZone(act: Activity) {
        runCatching { diagFile("EDGE PANEL home=${runCatching { isHomePageActive(act) }.getOrDefault(false)}") }
        ensureDragPanel(act)
        val panel = panelRootView as? FrameLayout ?: return
        panel.getChildAt(1)?.animate()?.translationX(0f)?.setDuration(220)?.start()
        panel.getChildAt(0)?.animate()?.alpha(0.66f)?.setDuration(220)?.start()
        hideTrigger()
        loadPanelData(act)
    }

    private fun handleDispatchTouch(param: XC_MethodHook.MethodHookParam) {
        if (!masterEnabled) return
        val mode = migratedTriggerMode()
        if ((mode and MODE_FULL_SWIPE) == 0) return
        // 面板已展开（非拖拽中）：不劫持，面板内交互由面板自身处理
        if (panelRootView != null && gestureState != GESTURE_DRAGGING) return
        val act = param.thisObject as? Activity ?: return
        // 精确限定只响应微信主页 Activity，杜绝 hook 命中基类 dispatchTouchEvent
        // 时在 ChattingUI 等其它页面全局呼出侧边栏。
        if (act.javaClass.name != "com.tencent.mm.ui.LauncherUI") return
        val ev = param.args[0] as? MotionEvent ?: return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val homeDown = isHomePageActive(act)
                runCatching { diagFile("GEST DOWN home=$homeDown state=$gestureState x=${ev.rawX.toInt()} y=${ev.rawY.toInt()}") }
                // Tab 切换后刷新入口显隐（切到非「微信」Tab 时移除按钮/面板）
                runCatching { updateVisibility() }
                if (gestureState == GESTURE_IDLE && homeDown) {
                    gestureDownX = ev.rawX
                    gestureDownY = ev.rawY
                    gestureState = GESTURE_ARMED
                } else {
                    gestureState = GESTURE_IDLE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - gestureDownX
                when (gestureState) {
                    GESTURE_ARMED -> {
                        val dy = ev.rawY - gestureDownY
                        val slopPx = (18 * act.resources.displayMetrics.density).toFloat()
                        if (dx > slopPx && Math.abs(dy) <= dx * 1.35f) {
                            // 判定为朝右的水平拖拽 → 接管为侧栏跟手拖拽
                            gestureState = GESTURE_DRAGGING
                            cancelContentGesture(act, ev)
                            ensureDragPanel(act)
                            updateDragPanel(act, dx)
                            param.result = true
                        } else if (Math.abs(dx) > slopPx * 8 || Math.abs(dy) > slopPx * 8) {
                            // 明显不是右滑手势（上下滚动/左滑等），放弃本次跟踪
                            gestureState = GESTURE_IDLE
                        }
                    }
                    GESTURE_DRAGGING -> {
                        updateDragPanel(act, dx)
                        param.result = true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (gestureState == GESTURE_DRAGGING) {
                    finishDrag(act, ev.rawX - gestureDownX)
                    param.result = true
                }
                gestureState = GESTURE_IDLE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (gestureState == GESTURE_DRAGGING) closeDragPanel(act)
                gestureState = GESTURE_IDLE
            }
        }
    }

    /** 向微信内容视图发送 ACTION_CANCEL，取消其正在跟踪的原生手势（滚动/长按/翻页）。 */
    private fun cancelContentGesture(act: Activity, ev: MotionEvent) {
        val decor = act.window?.decorView as? ViewGroup ?: return
        if (decor.childCount == 0) return
        val cancel = MotionEvent.obtain(ev.downTime, ev.eventTime, MotionEvent.ACTION_CANCEL, ev.x, ev.y, ev.metaState)
        runCatching { decor.getChildAt(0).dispatchTouchEvent(cancel) }
        cancel.recycle()
    }

    /** 跟手拖拽时确保面板已创建（先置于屏幕外，不播动画），并隐藏左上角触发按钮。 */
    private fun ensureDragPanel(act: Activity) {
        if (panelRootView != null) return
        runCatching { diagFile("ENSURE PANEL act=${act.javaClass.simpleName} home=${runCatching { isHomePageActive(act) }.getOrDefault(false)}") }
        buildPanel(act)
        hideTrigger()
    }

    /** 更新跟手位置：面板随手指平移，遮罩随开合比例渐变。 */
    private fun updateDragPanel(act: Activity, posPx: Float) {
        val panel = panelRootView as? FrameLayout ?: return
        val widthPx = dp(300, act).toFloat()
        val clamped = posPx.coerceIn(0f, widthPx)
        val progress = clamped / widthPx
        panel.getChildAt(1)?.translationX = clamped - widthPx
        panel.getChildAt(0)?.alpha = 0.66f * progress
    }

    /** 跟手到阈值以上：平滑展开到全开，并执行与按钮打开一致的收尾（隐藏触发按钮 + 加载面板数据）。 */
    private fun finishDrag(act: Activity, dx: Float) {
        val panel = panelRootView as? FrameLayout ?: return
        val widthPx = dp(300, act).toFloat()
        if (dx >= widthPx * 0.45f) {
            panel.getChildAt(1)?.animate()?.translationX(0f)?.setDuration(180)?.start()
            panel.getChildAt(0)?.animate()?.alpha(0.66f)?.setDuration(180)?.start()
            hideTrigger()
            loadPanelData(act)
        } else {
            closeDragPanel(act)
        }
    }

    /** 跟手未达阈值：平滑收回并清理。 */
    private fun closeDragPanel(act: Activity) {
        val panel = panelRootView as? FrameLayout ?: return
        val composeView = panel.getChildAt(1)
        val scrim = panel.getChildAt(0)
        composeView?.animate()?.translationX(-dp(300, act).toFloat())?.setDuration(180)
            ?.withEndAction {
                removePanelInternal()
                reattachTriggers()
            }?.start() ?: run { removePanelInternal(); reattachTriggers() }
        scrim?.animate()?.alpha(0f)?.setDuration(150)?.start()
    }

    /** 构建侧栏面板（遮罩 + 300dp ComposeView），初始置于屏幕外，不做开启动画。 */
    private fun buildPanel(act: Activity): FrameLayout? {
        val decorView = act.window?.decorView as? ViewGroup ?: return null
        return try {
            val dark = isDarkMode(act)
            val panel = FrameLayout(act).apply { tag = "home_side_panel_overlay" }
            val scrim = View(act).apply {
                setBackgroundColor(AndroidColor.parseColor("#66000000"))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setOnClickListener { try { hidePanel() } catch (e: Throwable) { WeLogger.e(TAG, "scrim 异常", e) } }
            }
            panel.addView(scrim)
            val composeView = ComposeView(act).apply {
                tag = "home_side_panel_compose"
                layoutParams = FrameLayout.LayoutParams(dp(300, act), ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.START or Gravity.TOP }
                try { setLifecycleOwner(LifecycleOwnerProvider.lifecycleOwner) } catch (e: Throwable) { WeLogger.w(TAG, "setLifecycleOwner 失败", e) }
                setContent {
                    InjectedUiTheme(darkTheme = dark) {
                        val primary = Color(if (dark) 0xFF64B5F6 else 0xFF1976D2)
                        val onPrimary = Color(if (dark) 0xFF003057 else 0xFFFFFFFF)
                        val primaryContainer = Color(if (dark) 0xFF00497A else 0xFFE3F2FD)
                        val onPrimaryContainer = Color(if (dark) 0xFFCDE5FF else 0xFF0D47A1)
                        val scheme = MaterialTheme.colorScheme.copy(
                            primary = primary,
                            onPrimary = onPrimary,
                            primaryContainer = primaryContainer,
                            onPrimaryContainer = onPrimaryContainer,
                            secondary = primary,
                            onSecondary = onPrimary,
                            secondaryContainer = primaryContainer,
                            onSecondaryContainer = onPrimaryContainer
                        )
                        MaterialTheme(colorScheme = scheme) {
                            SidePanelContent(act)
                        }
                    }
                }
            }
            panel.addView(composeView)
            composeView.translationX = -dp(300, act).toFloat()
            scrim.alpha = 0f
            val panelLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (panel.parent != null) (panel.parent as? ViewGroup)?.removeView(panel)
            decorView.addView(panel, panelLp)
            panelRootView = panel
            panel
        } catch (e: Throwable) {
            WeLogger.e(TAG, "buildPanel 异常", e)
            try { removePanelInternal() } catch (_: Throwable) {}
            null
        }
    }

    private fun showPanel(act: Activity) {
        if (panelRootView != null) return
        try {
            buildPanel(act) ?: return
            val panel = panelRootView as? FrameLayout ?: return
            val composeView = panel.getChildAt(1)
            val scrim = panel.getChildAt(0)
            // 开启动画：ComposeView 从左边滑入 + 遮罩渐显
            composeView?.translationX = -dp(300, act).toFloat()
            scrim?.alpha = 0f
            composeView?.animate()?.translationX(0f)?.setDuration(350)?.start()
            scrim?.animate()?.alpha(1f)?.setDuration(300)?.start()
            hideTrigger()
            loadPanelData(act)
        } catch (e: Throwable) { WeLogger.e(TAG, "showPanel 异常", e); try { removePanelInternal() } catch (_: Throwable) {} }
    }

    private fun makeRippleBg(color: Int): android.graphics.drawable.Drawable {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            RippleDrawable(android.content.res.ColorStateList.valueOf(color), null, null)
        } else GradientDrawable().apply { setColor(color) }
    }

    private fun removePanelInternal() {
        val panel = panelRootView ?: return
        try { if (panel.parent != null) (panel.parent as? ViewGroup)?.removeView(panel) }
        catch (e: Throwable) { WeLogger.w(TAG, "移除面板失败", e) }
        panelRootView = null
    }

    private fun hidePanel() {
        val panel = panelRootView ?: return
        val vg = panel as? ViewGroup
        val composeView = vg?.getChildAt(1)
        val scrim = vg?.getChildAt(0)
        val widthPx = dp(300, panel.context)
        composeView?.animate()?.translationX(-widthPx.toFloat())?.setDuration(250)?.withEndAction {
            removePanelInternal()
            reattachTriggers()
        }?.start() ?: run { removePanelInternal(); reattachTriggers() }
        scrim?.animate()?.alpha(0f)?.setDuration(200)?.start()
    }

    private fun reattachTriggers() {
        try {
            val act = attachedActivity ?: return
            if (!isHomePageActive(act)) return
            val mode = migratedTriggerMode()
            if ((mode and MODE_FULL_SWIPE) != 0 && !gestureHookInstalled) attachEdgeZone(act)
            if ((mode and MODE_EDGE_STRIP) != 0 && edgeZoneStripView == null) attachEdgeZoneStrip(act)
            if ((mode and MODE_TRIGGER_BUTTON) != 0 &&
                (triggerButtonView == null || triggerButtonView!!.parent == null)
            ) attachTriggerButton(act)
        } catch (e: Throwable) { WeLogger.e(TAG, "hidePanel 重挂触发视图异常", e) }
    }

    private fun removeAllViews() {
        try {
            stopVisibilityPoller()
            removePanelInternal()
            removeEdgeZone()
            removeEdgeZoneStrip()
            removeTriggerButton()
        } catch (e: Throwable) { WeLogger.e(TAG, "removeAllViews 异常", e) }
    }

    // ==================== 面板数据加载（异步喂给 Compose 状态） ====================
    private fun loadPanelData(act: Activity) {
        try {
            mainHandler.post {
                uiTime = try { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) } catch (_: Throwable) { "00:00" }
                uiDate = try { SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE).format(Date()) } catch (_: Throwable) { "" }
            }
            Thread {
                try { val v = fetchQuoteText(); mainHandler.post { uiSignature = v } } catch (_: Throwable) {}
            }.start()
            Thread {
                try { val v = fetchDailyQuote(); mainHandler.post { uiDailyQuote = v } } catch (_: Throwable) {}
            }.start()
            Thread {
                try {
                    val w = fetchWeatherData()
                    if (w != null) mainHandler.post { uiWeather = w }
                } catch (e: Throwable) { WeLogger.e(TAG, "weather async error", e) }
            }.start()
            Thread {
                try {
                    var bmp: Bitmap? = null
                    var attempts = 0
                    while (bmp == null && attempts < 3) {
                        bmp = loadAvatarBitmap(act)
                        if (bmp == null) { try { Thread.sleep(1500) } catch (_: Throwable) {}; attempts++ }
                    }
                    val nick = loadWeChatNickname(act)
                    val finalBmp = bmp
                    mainHandler.post {
                        try {
                            if (finalBmp != null) uiAvatar = finalBmp
                            if (nick.isNotBlank()) uiNickname = nick
                        } catch (_: Throwable) {}
                    }
                } catch (e: Throwable) { WeLogger.e(TAG, "loadPanelData avatar 失败", e) }
            }.start()
        } catch (e: Throwable) { WeLogger.e(TAG, "loadPanelData 异常", e) }
    }

    // ==================== 头部卡片（头像 + 签名） ====================
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun HeaderCard(act: Activity) {
        val cs = MaterialTheme.colorScheme
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val bmp = uiAvatar
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "头像",
                            modifier = Modifier.size(56.dp).clip(CircleShape)
                                .combinedClickable(onClick = {}, onLongClick = { try { showAvatarConfigDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "头像配置异常", e); showToast("头像配置失败：" + e.message) } }),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(56.dp).clip(CircleShape).background(cs.primaryContainer)
                                .combinedClickable(onClick = {}, onLongClick = { try { showAvatarConfigDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "头像配置异常", e); showToast("头像配置失败：" + e.message) } }),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(MaterialSymbols.OutlinedFilled.Person, "头像", tint = cs.onPrimaryContainer)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { try { showStatusConfigDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "状态配置异常", e); showToast("状态配置失败：" + e.message) } })
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(if (isOnline) 0xFF4CAF50 else 0xFF9E9E9E))) {}
                            Spacer(Modifier.width(6.dp))
                            Text(onlineStatus, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(uiNickname.ifBlank { "微信" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(uiTime, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
                        Text(uiDate, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = { loadPanelData(act) }) {
                        Icon(MaterialSymbols.OutlinedFilled.Update, "刷新", tint = cs.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    uiSignature.ifBlank { quoteFallback },
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { try { showSignatureConfigDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "签名配置异常", e); showToast("签名配置失败：" + e.message) } })
                )
            }
        }
    }

    // ==================== 天气卡片 — WeKit 风格（长按配置） ====================
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun WeatherCard(act: Activity) {
        val cs = MaterialTheme.colorScheme
        val w = uiWeather
        Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { try { showWeatherConfigDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "天气配置异常", e); showToast("天气配置失败：" + e.message) } }),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                if (w == null) {
                    // 加载占位：紧凑单行，避免 "--" 大字占位撑高卡片
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(weatherCity.ifBlank { weatherFallbackCity }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Text("天气加载中…", style = MaterialTheme.typography.bodySmall, color = cs.primary)
                    }
                } else {
                    // 城市+天气描述+更新时间
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(w.city, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!w.weather.isNullOrBlank()) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(w.weather, style = MaterialTheme.typography.bodySmall, color = cs.primary, maxLines = 1)
                                }
                            }
                            Text(w.updateTime, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                        }
                        Text(w.weatherIcon?.takeIf { it.isNotBlank() } ?: "☁", fontSize = 26.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    // 温度区：温度大字 + 高低/体感；湿度/风速独立一行左右分布，避免窄面板右侧挤压遮挡
                    Column(
                        modifier = Modifier.fillMaxWidth().background(cs.primaryContainer.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(w.temperature, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("${w.tempHigh} / ${w.tempLow}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer, maxLines = 1)
                                if (!w.feelsLike.isNullOrBlank()) {
                                    Text("体感 ${w.feelsLike}", style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer.copy(alpha = 0.7f), maxLines = 1)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("湿度 ${formatHumidity(w.humidity)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                            Spacer(Modifier.weight(1f))
                            Text("风速 ${formatWindSpeed(w.windSpeed)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    // ==================== 4 槽快捷栏（固定 4 个，支持替换） ====================
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun QuickButtonsGrid(act: Activity) {
        val cs = MaterialTheme.colorScheme
        val slots = loadQuickSlots().take(4)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                slots.forEach { slot ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                            .combinedClickable(
                                onClick = { try { executeSlotTarget(act, slot) } catch (e: Throwable) { WeLogger.e(TAG, "slot ${slot.slotId} 点击异常", e) } },
                                onLongClick = { try { showSlotPickerDialog(act, slot.slotId, slot) } catch (e: Throwable) { WeLogger.e(TAG, "slot ${slot.slotId} 选择异常", e); showToast("快捷栏选择失败：" + e.message) } }
                            )
                    ) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(cs.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(iconFor(slot.iconName), slot.name, tint = cs.primary)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(slot.name, style = MaterialTheme.typography.labelMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    /** 执行 slot 绑定的功能跳转 */
    private fun executeSlotTarget(act: Activity, slot: SlotConfig) {
        when (slot.targetActivity) {
            "__clear_unread__" -> {
                try { Toast.makeText(act, "已尝试清空未读（占位）", Toast.LENGTH_SHORT).show() }
                catch (e: Throwable) { WeLogger.e(TAG, "清空未读异常", e) }
            }
            "__group_member__" -> {
                try { Toast.makeText(act, "群成员变动提醒设置入口", Toast.LENGTH_SHORT).show() }
                catch (e: Throwable) { WeLogger.e(TAG, "群成员入口异常", e) }
            }
            else -> startActivityByName(act, slot.targetActivity, slot.isCustomIntent)
        }
    }

    private class FeatureEntry(val icon: ImageVector, val label: String, val onClick: () -> Unit)

    private fun buildFeatureEntries(act: Activity): List<FeatureEntry> {
        val list = mutableListOf<FeatureEntry>()
        if (momentsEntryEnabled) list.add(FeatureEntry(MaterialSymbols.OutlinedFilled.Favorite, "朋友圈") { startActivityByName(act, "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI") })
        if (videoEntryEnabled) list.add(FeatureEntry(MaterialSymbols.OutlinedFilled.Movie, "视频号") { startActivityByName(act, "com.tencent.mm.plugin.finder.ui.FinderHomeUI") })
        if (clearUnreadEnabled) list.add(FeatureEntry(MaterialSymbols.OutlinedFilled.Check_circle, "清空未读") {
            try { Toast.makeText(act, "已尝试清空未读（占位）", Toast.LENGTH_SHORT).show() } catch (e: Throwable) { WeLogger.e(TAG, "清空未读异常", e) }
        })
        if (wcxSettingsEnabled) list.add(FeatureEntry(MaterialSymbols.OutlinedFilled.Settings, "WCX 设置") {
            try {
                val intent = Intent(act, Class.forName("com.Johnny.wcx.activity.settings.SettingsActivity")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                act.startActivity(intent)
            } catch (e: Throwable) { WeLogger.e(TAG, "WCX 设置启动异常", e); showToast("无法打开WCX设置") }
        })
        val customs = loadCustomFeatures()
        customs.forEach { cf ->
            list.add(FeatureEntry(iconFor(cf.iconName), cf.name) {
                if (cf.isCustomIntent) {
                    try {
                        val intent = Intent().apply { setClassName(act.packageName, cf.targetActivity); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        act.startActivity(intent)
                    } catch (e: Throwable) { WeLogger.e(TAG, "自定义Intent异常", e); showToast("无法打开该功能") }
                } else startActivityByName(act, cf.targetActivity, false)
            })
        }
        return list
    }

    @Composable
    private fun FeatureList(act: Activity) {
        val cs = MaterialTheme.colorScheme
        val entries = buildFeatureEntries(act)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column {
                entries.forEachIndexed { index, e ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { e.onClick() }.padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Icon(e.icon, e.label, tint = cs.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(e.label, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, modifier = Modifier.weight(1f))
                        Text("›", color = cs.outlineVariant, fontSize = 20.sp)
                    }
                    if (index < entries.lastIndex) {
                        Box(Modifier.fillMaxWidth().padding(start = 50.dp).height(1.dp).background(cs.outlineVariant.copy(alpha = 0.25f))) {}
                    }
                }
            }
        }
    }

    // ==================== 每日一言卡片（长按配置） ====================
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun QuoteCard(act: Activity) {
        val cs = MaterialTheme.colorScheme
        Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { try { showDailyQuoteConfigDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "每日一言配置异常", e); showToast("每日一言配置失败：" + e.message) } }),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("❝", color = cs.primary, fontSize = 20.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("每日一言", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
                }
                Spacer(Modifier.height(8.dp))
                Text(uiDailyQuote.ifBlank { dailyQuoteFallback }, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
    }

    // ==================== 面板整体 Compose 内容（悬浮液态玻璃） ====================
    @Composable
    private fun SidePanelContent(act: Activity) {
        val cs = MaterialTheme.colorScheme
        key(panelRefresh) {
            val panelShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
            Surface(
                modifier = Modifier.fillMaxSize()
                    .clip(panelShape)
                    .border(1.dp, cs.outlineVariant.copy(alpha = 0.3f), panelShape),
                color = cs.surfaceVariant
            ) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height((getStatusBarHeight(act) * 0.4).toInt().dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("侧边栏", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.onSurface, modifier = Modifier.weight(1f))
                        IconButton(onClick = { try { showPanelSettingsDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "设置按钮异常", e); showToast("配置面板打开失败：" + e.message) } }) {
                            Icon(MaterialSymbols.OutlinedFilled.Settings, "设置", tint = cs.primary)
                        }
                        IconButton(onClick = { try { hidePanel() } catch (e: Throwable) { WeLogger.e(TAG, "关闭按钮异常", e) } }) {
                            Icon(MaterialSymbols.OutlinedFilled.Close, "关闭", tint = cs.onSurfaceVariant)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant)) {}
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (headerEnabled) HeaderCard(act)
                        if (weatherEnabled) WeatherCard(act)
                        if (quickButtonsEnabled) QuickButtonsGrid(act)
                        FeatureList(act)
                        if (dailyQuoteEnabled) QuoteCard(act)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    private fun iconFor(iconName: String): ImageVector = when (iconName) {
        "Qr_code_scanner" -> MaterialSymbols.OutlinedFilled.Qr_code_scanner
        "Wallet" -> MaterialSymbols.OutlinedFilled.Wallet
        "Collections_bookmark" -> MaterialSymbols.OutlinedFilled.Bookmark
        "Favorite" -> MaterialSymbols.OutlinedFilled.Favorite
        "Person" -> MaterialSymbols.OutlinedFilled.Person
        "Settings" -> MaterialSymbols.OutlinedFilled.Settings
        "Camera" -> MaterialSymbols.OutlinedFilled.Camera
        "Video_call" -> MaterialSymbols.OutlinedFilled.Movie
        "Movie" -> MaterialSymbols.OutlinedFilled.Movie
        else -> MaterialSymbols.OutlinedFilled.Add
    }

    // ==================== 设置面板（开关 + 4 插槽选择 + 卡片配置入口）====================
    private fun showPanelSettingsDialog(act: Activity) {
        try {
            val container = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20, act), dp(12, act), dp(20, act), dp(12, act)) }
            // 1. 卡片显示开关
            val cardHeader = TextView(act).apply {
                text = "▌ 卡片显示"; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4, act); bottomMargin = dp(4, act) }
            }
            container.addView(cardHeader)
            addSwitchRow(container, act, "头部卡片（头像/签名/状态）", headerEnabled) { headerEnabled = it; refreshPanelContent(act) }
            addSwitchRow(container, act, "天气卡片", weatherEnabled) { weatherEnabled = it; refreshPanelContent(act) }
            addSwitchRow(container, act, "快捷功能栏", quickButtonsEnabled) { quickButtonsEnabled = it; refreshPanelContent(act) }
            addSwitchRow(container, act, "每日一言卡片", dailyQuoteEnabled) { dailyQuoteEnabled = it; refreshPanelContent(act) }

            // 2. 卡片独立配置入口
            val configHeader = TextView(act).apply {
                text = "▌ 卡片独立配置（长按卡片也可触发）"; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12, act); bottomMargin = dp(4, act) }
            }
            container.addView(configHeader)
            addConfigEntryRow(container, act, "头像配置（3 模式）") { showAvatarConfigDialog(act) }
            addConfigEntryRow(container, act, "签名/语录配置（3 模式）") { showSignatureConfigDialog(act) }
            addConfigEntryRow(container, act, "天气配置（API+Key）") { showWeatherConfigDialog(act) }
            addConfigEntryRow(container, act, "每日一言配置（API+Key）") { showDailyQuoteConfigDialog(act) }

            // 3. 快捷功能插槽（4 槽）
            val slotHeader = TextView(act).apply {
                text = "▌ 快捷功能（固定 4 槽，长按单槽可替换）"; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12, act); bottomMargin = dp(4, act) }
            }
            container.addView(slotHeader)
            val slots = loadQuickSlots().take(4)
            for (i in 0..3) {
                val slot = slots.getOrNull(i) ?: defaultSlotConfigs[i]
                addSlotEntryRow(container, act, i + 1, slot)
            }

            // 4. 重置 / 完成
            val scroll = ScrollView(act).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(container.also {
                    it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                })
            }
            AlertDialog.Builder(act)
                .setTitle("侧边栏设置")
                .setView(scroll)
                .setPositiveButton("完成") { d, _ -> d.dismiss() }
                .setNegativeButton("重置默认") { d, _ ->
                    try {
                        headerEnabled = true; weatherEnabled = true; quickButtonsEnabled = true
                        momentsEntryEnabled = true; videoEntryEnabled = true; clearUnreadEnabled = true
                        wcxSettingsEnabled = true; dailyQuoteEnabled = true
                        saveQuickSlots(defaultSlotConfigs)
                        saveCustomFeatures(emptyList())
                        showToast("已恢复默认")
                        refreshPanelContent(act)
                    } catch (e: Throwable) { WeLogger.e(TAG, "恢复默认异常", e) }
                    d.dismiss()
                }
                .show()
        } catch (e: Throwable) { WeLogger.e(TAG, "showPanelSettingsDialog 异常", e); showToast("配置面板打开失败") }
    }

    private fun refreshPanelContent(act: Activity) {
        try {
            panelRefresh = panelRefresh + 1
        } catch (e: Throwable) { WeLogger.e(TAG, "refreshPanelContent 异常", e) }
    }

    private fun addSwitchRow(container: LinearLayout, act: Activity, label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        try {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { val m = dp(4, act); setMargins(0, m, 0, m) }
            }
            val tv = TextView(act).apply {
                text = label; textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sw = android.widget.Switch(act).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, c -> try { onChange(c) } catch (e: Throwable) { WeLogger.e(TAG, "switch 异常", e) } }
            }
            row.addView(tv); row.addView(sw); container.addView(row)
        } catch (e: Throwable) { WeLogger.e(TAG, "addSwitchRow 异常", e) }
    }

    private fun addConfigEntryRow(container: LinearLayout, act: Activity, label: String, onClick: () -> Unit) {
        try {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { val m = dp(2, act); setMargins(0, m, 0, m) }
                setPadding(0, dp(8, act), 0, dp(8, act))
                background = makeRippleBg(AndroidColor.parseColor("#11000000"))
                setOnClickListener { onClick() }
            }
            val tv = TextView(act).apply { text = label; textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            val arrow = TextView(act).apply { text = "›"; textSize = 20f; setTextColor(AndroidColor.parseColor("#888888")) }
            row.addView(tv); row.addView(arrow); container.addView(row)
        } catch (e: Throwable) { WeLogger.e(TAG, "addConfigEntryRow 异常", e) }
    }

    private fun addSlotEntryRow(container: LinearLayout, act: Activity, slotId: Int, current: SlotConfig) {
        try {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { val m = dp(2, act); setMargins(0, m, 0, m) }
                setPadding(0, dp(6, act), 0, dp(6, act))
                background = makeRippleBg(AndroidColor.parseColor("#11000000"))
                setOnClickListener { try { showSlotPickerDialog(act, slotId, current) } catch (e: Throwable) { WeLogger.e(TAG, "slot 选择异常", e) } }
            }
            val tvLabel = TextView(act).apply {
                text = "槽位 $slotId"; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(dp(60, act), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val tvValue = TextView(act).apply {
                text = "${iconPool[current.iconName] ?: "?"} ${current.name}"; textSize = 13f
                setTextColor(AndroidColor.parseColor("#1976D2"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8, act) }
            }
            val arrow = TextView(act).apply { text = "›"; textSize = 20f; setTextColor(AndroidColor.parseColor("#888888")) }
            row.addView(tvLabel); row.addView(tvValue); row.addView(arrow); container.addView(row)
        } catch (e: Throwable) { WeLogger.e(TAG, "addSlotEntryRow 异常", e) }
    }

    // ==================== 4 槽选择弹窗（slot picker）====================
    private fun showSlotPickerDialog(act: Activity, slotId: Int, current: SlotConfig) {
        try {
            val items = mutableListOf<Pair<String, SlotConfig>>()
            // 微信原生
            for ((name, icon, target) in wechatNativeTargets) {
                items.add("$icon $name" to SlotConfig(slotId, name, iconToIconName(icon), target))
            }
            // 模块内置
            for ((name, icon, target) in moduleTargets()) {
                items.add("$icon $name（模块）" to SlotConfig(slotId, name, iconToIconName(icon), target))
            }
            // 自定义功能
            val customs = loadCustomFeatures()
            for (cf in customs) {
                items.add("${iconPool[cf.iconName] ?: "+"} ${cf.name}（自定义）" to SlotConfig(slotId, cf.name, cf.iconName, cf.targetActivity, cf.isCustomIntent))
            }
            if (items.isEmpty()) {
                showToast("暂无可用功能，请先添加自定义功能")
                return
            }
            val labels = items.map { it.first }.toTypedArray()
            AlertDialog.Builder(act)
                .setTitle("选择槽位 $slotId 的功能")
                .setItems(labels) { _, which ->
                    try {
                        val list = loadQuickSlots().toMutableList()
                        // 确保 list 长度 ≥ 4
                        while (list.size < 4) list.add(defaultSlotConfigs[list.size])
                        val newSlot = items[which].second.copy(slotId = slotId)
                        list[slotId - 1] = newSlot
                        saveQuickSlots(list)
                        refreshPanelContent(act)
                        showToast("槽位 $slotId 已切换为 ${newSlot.name}")
                    } catch (e: Throwable) { WeLogger.e(TAG, "slot 保存异常", e) }
                }
                .setNegativeButton("重置为默认") { _, _ ->
                    try {
                        val list = loadQuickSlots().toMutableList()
                        while (list.size < 4) list.add(defaultSlotConfigs[list.size])
                        list[slotId - 1] = defaultSlotConfigs[slotId - 1]
                        saveQuickSlots(list)
                        refreshPanelContent(act)
                        showToast("槽位 $slotId 已重置")
                    } catch (e: Throwable) { WeLogger.e(TAG, "重置异常", e) }
                }
                .show()
        } catch (e: Throwable) { WeLogger.e(TAG, "showSlotPickerDialog 异常", e); showToast("快捷栏选择打开失败：" + e.message) }
    }

    /** icon emoji → iconName 映射 */
    private fun iconToIconName(emoji: String): String = when (emoji) {
        "🔍" -> "Qr_code_scanner"
        "💰" -> "Wallet"
        "★" -> "Collections_bookmark"
        "♥" -> "Favorite"
        "☻" -> "Person"
        "+" -> "Add"
        "↻" -> "Refresh"
        "⚙" -> "Settings"
        "☁" -> "Cloud"
        "✕" -> "Delete"
        "◉" -> "Camera"
        "▦" -> "Photo"
        "▶" -> "Video_call"
        else -> "Add"
    }

    // ==================== 头像配置弹窗（3 模式）====================
    private fun showAvatarConfigDialog(act: Activity) {
        try {
            val modeHolder = intArrayOf(avatarMode)
            val container = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20, act), dp(12, act), dp(20, act), dp(12, act)) }

            val radioGroup = RadioGroup(act).apply {
                orientation = RadioGroup.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val r0 = RadioButton(act).apply { text = "模式1：自动读取微信原生头像"; id = 100; isChecked = avatarMode == 0 }
            val r1 = RadioButton(act).apply { text = "模式2：自定义本地图片（输入路径）"; id = 101; isChecked = avatarMode == 1 }
            val r2 = RadioButton(act).apply { text = "模式3：自定义网络 URL"; id = 102; isChecked = avatarMode == 2 }
            radioGroup.addView(r0); radioGroup.addView(r1); radioGroup.addView(r2)
            radioGroup.setOnCheckedChangeListener { _, id -> modeHolder[0] = when (id) { 101 -> 1; 102 -> 2; else -> 0 } }
            container.addView(radioGroup)

            val localInput = EditText(act).apply { setText(avatarLocalPath); hint = "本地图片绝对路径（如 /sdcard/Pictures/avatar.png）"; visibility = if (avatarMode == 1) android.view.View.VISIBLE else android.view.View.GONE }
            val urlInput = EditText(act).apply { setText(avatarUrl); hint = "网络图片 URL（http/https）"; visibility = if (avatarMode == 2) android.view.View.VISIBLE else android.view.View.GONE }
            container.addView(localInput); container.addView(urlInput)
            r0.setOnClickListener { localInput.visibility = android.view.View.GONE; urlInput.visibility = android.view.View.GONE }
            r1.setOnClickListener { localInput.visibility = android.view.View.VISIBLE; urlInput.visibility = android.view.View.GONE }
            r2.setOnClickListener { localInput.visibility = android.view.View.GONE; urlInput.visibility = android.view.View.VISIBLE }

            AlertDialog.Builder(act)
                .setTitle("头像配置")
                .setView(ScrollView(act).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    addView(container.also {
                        it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                })
                .setPositiveButton("保存") { d, _ ->
                    try {
                        avatarMode = modeHolder[0]
                        avatarLocalPath = localInput.text.toString()
                        avatarUrl = urlInput.text.toString()
                        refreshPanelContent(act)
                        showToast("头像配置已保存")
                    } catch (e: Throwable) { WeLogger.e(TAG, "保存头像配置异常", e) }
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Throwable) { WeLogger.e(TAG, "showAvatarConfigDialog 异常", e); showToast("头像配置打开失败：" + e.message) }
    }

    // ==================== 签名配置弹窗（3 模式）====================
    private fun showSignatureConfigDialog(act: Activity) {
        try {
            val modeHolder = intArrayOf(quoteMode)
            val container = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20, act), dp(12, act), dp(20, act), dp(12, act)) }

            val radioGroup = RadioGroup(act).apply { orientation = RadioGroup.VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
            val r0 = RadioButton(act).apply { text = "模式1：自动获取微信个人签名"; id = 200; isChecked = quoteMode == 0 }
            val r1 = RadioButton(act).apply { text = "模式2：自定义文本（手动输入）"; id = 201; isChecked = quoteMode == 1 }
            val r2 = RadioButton(act).apply { text = "模式3：API+Key 远程拉取"; id = 202; isChecked = quoteMode == 2 }
            radioGroup.addView(r0); radioGroup.addView(r1); radioGroup.addView(r2)
            radioGroup.setOnCheckedChangeListener { _, id -> modeHolder[0] = when (id) { 201 -> 1; 202 -> 2; else -> 0 } }
            container.addView(radioGroup)

            val manualInput = EditText(act).apply { setText(quoteManual); hint = "手动签名文本"; visibility = if (quoteMode == 1) android.view.View.VISIBLE else android.view.View.GONE }
            val apiUrlInput = EditText(act).apply { setText(quoteApiUrl); hint = "签名 API 地址"; visibility = if (quoteMode == 2) android.view.View.VISIBLE else android.view.View.GONE }
            val apiKeyInput = EditText(act).apply { setText(quoteApiKey); hint = "API Key（可选）"; visibility = if (quoteMode == 2) android.view.View.VISIBLE else android.view.View.GONE }
            val fallbackInput = EditText(act).apply { setText(quoteFallback); hint = "兜底签名（API 失败时显示）"; visibility = if (quoteMode == 2) android.view.View.VISIBLE else android.view.View.GONE }
            container.addView(manualInput); container.addView(apiUrlInput); container.addView(apiKeyInput); container.addView(fallbackInput)

            r0.setOnClickListener { manualInput.visibility = android.view.View.GONE; apiUrlInput.visibility = android.view.View.GONE; apiKeyInput.visibility = android.view.View.GONE; fallbackInput.visibility = android.view.View.GONE }
            r1.setOnClickListener { manualInput.visibility = android.view.View.VISIBLE; apiUrlInput.visibility = android.view.View.GONE; apiKeyInput.visibility = android.view.View.GONE; fallbackInput.visibility = android.view.View.GONE }
            r2.setOnClickListener { manualInput.visibility = android.view.View.GONE; apiUrlInput.visibility = android.view.View.VISIBLE; apiKeyInput.visibility = android.view.View.VISIBLE; fallbackInput.visibility = android.view.View.VISIBLE }

            AlertDialog.Builder(act)
                .setTitle("签名/语录配置")
                .setView(ScrollView(act).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    addView(container.also {
                        it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                })
                .setPositiveButton("保存") { d, _ ->
                    try {
                        quoteMode = modeHolder[0]
                        quoteManual = manualInput.text.toString()
                        quoteApiUrl = apiUrlInput.text.toString()
                        quoteApiKey = apiKeyInput.text.toString()
                        quoteFallback = fallbackInput.text.toString()
                        refreshPanelContent(act)
                        showToast("签名配置已保存")
                    } catch (e: Throwable) { WeLogger.e(TAG, "保存签名配置异常", e) }
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Throwable) { WeLogger.e(TAG, "showSignatureConfigDialog 异常", e); showToast("签名配置打开失败：" + e.message) }
    }

    // ==================== 状态行配置弹窗 ====================
    private fun showStatusConfigDialog(act: Activity) {
        try {
            val onlineHolder = booleanArrayOf(isOnline)
            val container = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20, act), dp(12, act), dp(20, act), dp(12, act)) }
            val swRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val swLabel = TextView(act).apply { text = "在线"; textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            val sw = Switch(act).apply { isChecked = onlineHolder[0]; setOnCheckedChangeListener { _, c -> onlineHolder[0] = c } }
            swRow.addView(swLabel); swRow.addView(sw); container.addView(swRow)
            val input = EditText(act).apply { setText(onlineStatus); hint = "状态描述" }
            container.addView(input)
            AlertDialog.Builder(act)
                .setTitle("在线状态配置")
                .setView(container)
                .setPositiveButton("保存") { d, _ ->
                    try { isOnline = onlineHolder[0]; onlineStatus = input.text.toString(); refreshPanelContent(act) } catch (e: Throwable) { WeLogger.e(TAG, "保存状态异常", e) }
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Throwable) { WeLogger.e(TAG, "showStatusConfigDialog 异常", e); showToast("状态配置打开失败：" + e.message) }
    }

    // ==================== 天气配置弹窗（API+Key，完整保存）====================
    private fun showWeatherConfigDialog(act: Activity) {
        try {
            val cityHolder = arrayOf(weatherCity)
            val urlHolder = arrayOf(weatherApiUrl)
            val keyHolder = arrayOf(weatherApiKey)
            val fbHolder = arrayOf(weatherFallbackCity)
            val container = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20, act), dp(12, act), dp(20, act), dp(12, act)) }
            container.addView(EditText(act).apply { setText(cityHolder[0]); hint = "城市（如 北京）" })
            container.addView(EditText(act).apply { setText(urlHolder[0]); hint = "天气 API 地址（留空=内置 Open-Meteo）" })
            container.addView(EditText(act).apply { setText(keyHolder[0]); hint = "API Key（可选）" })
            container.addView(EditText(act).apply { setText(fbHolder[0]); hint = "兜底城市（API 失败时显示）" })
            val inputs = (0 until container.childCount).map { container.getChildAt(it) as EditText }
            AlertDialog.Builder(act)
                .setTitle("天气配置")
                .setView(ScrollView(act).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    addView(container.also {
                        it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                })
                .setPositiveButton("保存") { d, _ ->
                    try {
                        weatherCity = inputs[0].text.toString().ifBlank { "北京" }
                        weatherApiUrl = inputs[1].text.toString()
                        weatherApiKey = inputs[2].text.toString()
                        weatherFallbackCity = inputs[3].text.toString().ifBlank { "北京" }
                        cachedWeather = null  // 清缓存，下次重取
                        lastWeatherFetchTime = 0L
                        refreshPanelContent(act)
                        showToast("天气配置已保存")
                    } catch (e: Throwable) { WeLogger.e(TAG, "保存天气异常", e) }
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Throwable) { WeLogger.e(TAG, "showWeatherConfigDialog 异常", e); showToast("天气配置打开失败：" + e.message) }
    }

    // ==================== 每日一言配置弹窗（API+Key，完整保存）====================
    private fun showDailyQuoteConfigDialog(act: Activity) {
        try {
            val urlHolder = arrayOf(dailyQuoteApiUrl)
            val keyHolder = arrayOf(dailyQuoteApiKey)
            val fbHolder = arrayOf(dailyQuoteFallback)
            val container = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20, act), dp(12, act), dp(20, act), dp(12, act)) }
            container.addView(EditText(act).apply { setText(urlHolder[0]); hint = "每日一言 API 地址" })
            container.addView(EditText(act).apply { setText(keyHolder[0]); hint = "API Key（可选）" })
            container.addView(EditText(act).apply { setText(fbHolder[0]); hint = "兜底文案（API 失败时显示）" })
            val inputs = (0 until container.childCount).map { container.getChildAt(it) as EditText }
            AlertDialog.Builder(act)
                .setTitle("每日一言配置")
                .setView(ScrollView(act).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    addView(container.also {
                        it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                })
                .setPositiveButton("保存") { d, _ ->
                    try {
                        dailyQuoteApiUrl = inputs[0].text.toString()
                        dailyQuoteApiKey = inputs[1].text.toString()
                        dailyQuoteFallback = inputs[2].text.toString().ifBlank { "生活不止眼前的苟且，还有诗和远方" }
                        cachedDailyQuote = ""
                        lastDailyQuoteFetchTime = 0L
                        dailyQuoteCacheText = ""
                        dailyQuoteCacheTime = 0L
                        refreshPanelContent(act)
                        showToast("每日一言配置已保存")
                    } catch (e: Throwable) { WeLogger.e(TAG, "保存每日一言异常", e) }
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Throwable) { WeLogger.e(TAG, "showDailyQuoteConfigDialog 异常", e); showToast("每日一言配置打开失败：" + e.message) }
    }

    // ==================== 工具方法 ====================
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun isDarkMode(act: Activity): Boolean = try {
        (act.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    } catch (_: Throwable) { false }

    private fun dp(value: Int, ctx: Context): Int = try {
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics).toInt()
    } catch (_: Throwable) { value }

    private fun getStatusBarHeight(act: Activity): Int = try {
        val resourceId = act.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) act.resources.getDimensionPixelSize(resourceId) else 0
    } catch (_: Throwable) { 0 }

    private fun showToast(text: String) {
        try {
            val app = currentApplication() ?: return
            Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) { WeLogger.w(TAG, "showToast 失败", e) }
    }

    // ==================== 数据获取（带超时 + 兜底）====================
    /** weather data: Open-Meteo primary, wttr.in fallback, custom API optional */
    private fun fetchWeatherData(): WeatherData? {
        val city = if (weatherCity.isBlank()) weatherFallbackCity else weatherCity
        val now = System.currentTimeMillis()
        // cache is keyed by city so a city change always invalidates it
        val cachedW = cachedWeather
        if (cachedW != null && cachedW.city == city &&
            now - lastWeatherFetchTime < weatherRefreshInterval * 1000L) return cachedW
        return try {
            val w = if (useOpenMeteoSource()) {
                fetchOpenMeteoWeather(city) ?: fetchWttrWeather(city)
            } else {
                fetchCustomApiWeather(city)
            }
            if (w != null) { cachedWeather = w; lastWeatherFetchTime = now }
            w ?: cachedWeather
        } catch (e: Throwable) {
            WeLogger.e(TAG, "get weather failed", e)
            cachedWeather
        }
    }

    /** true when using the built-in Open-Meteo source (URL blank or still old default) */
    private fun useOpenMeteoSource(): Boolean {
        val u = weatherApiUrl.trim()
        return u.isEmpty() || u == DEFAULT_WEATHER_API_URL
    }

    /** custom API source ({city} placeholder is replaced with the city name) */
    private fun fetchCustomApiWeather(city: String): WeatherData? {
        var apiUrl = weatherApiUrl
        if (apiUrl.isBlank()) return null
        if (apiUrl.contains(DEAD_API_DOMAIN)) apiUrl = DEFAULT_WEATHER_API_URL
        val finalUrl = apiUrl.replace("{city}", URLEncoder.encode(city, "UTF-8"))
        val json = httpGet(finalUrl, weatherApiKey) ?: return null
        return parseWeatherJson(json, city)
    }

    /** Open-Meteo: geocode (supports Chinese city names) + current/daily forecast */
    private fun fetchOpenMeteoWeather(city: String): WeatherData? {
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(city, "UTF-8") + "&count=1&language=zh&format=json"
        val geoJson = httpGet(geoUrl) ?: return null
        val geo = org.json.JSONObject(geoJson)
        val results = geo.optJSONArray("results") ?: return null
        val first = results.optJSONObject(0) ?: return null
        val displayCity = first.optString("name", city).ifBlank { city }
        val lat = first.optDouble("latitude", Double.NaN)
        val lon = first.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        val fcUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
        val fcJson = httpGet(fcUrl) ?: return null
        val fc = org.json.JSONObject(fcJson)
        val cur = fc.optJSONObject("current") ?: return null
        val daily = fc.optJSONObject("daily") ?: return null
        val code = cur.optInt("weather_code", -1)
        val maxArr = daily.optJSONArray("temperature_2m_max")
        val minArr = daily.optJSONArray("temperature_2m_min")
        return WeatherData(
            city = displayCity,
            temperature = omTemp(cur.optDouble("temperature_2m", Double.NaN)),
            feelsLike = omTemp(cur.optDouble("apparent_temperature", Double.NaN)),
            tempHigh = omTemp(if (maxArr != null && maxArr.length() > 0) maxArr.optDouble(0, Double.NaN) else Double.NaN),
            tempLow = omTemp(if (minArr != null && minArr.length() > 0) minArr.optDouble(0, Double.NaN) else Double.NaN),
            humidity = cur.optString("relative_humidity_2m", "--"),
            windSpeed = cur.optString("wind_speed_10m", "--") + " km/h",
            weather = openMeteoDesc(code),
            updateTime = try { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) } catch (_: Throwable) { "" },
            weatherIcon = openMeteoEmoji(code)
        )
    }

    /** wttr.in fallback source */
    private fun fetchWttrWeather(city: String): WeatherData? {
        val url = DEFAULT_WEATHER_API_URL.replace("{city}", URLEncoder.encode(city, "UTF-8"))
        val json = httpGet(url) ?: return null
        return parseWeatherJson(json, city)
    }

    private fun omTemp(v: Double): String =
        if (v.isNaN()) "--" else (Math.round(v)).toString() + "\u00b0"

    /** 湿度补全百分号（"--"/空值原样返回） */
    private fun formatHumidity(v: String?): String {
        if (v.isNullOrBlank() || v == "--") return "--"
        return if (v.endsWith("%")) v else "$v%"
    }

    /** 风速补全单位（"--"/空值原样返回） */
    private fun formatWindSpeed(v: String?): String {
        if (v.isNullOrBlank() || v == "--") return "--"
        return if (v.contains("km/h")) v else "$v km/h"
    }

    private fun openMeteoDesc(code: Int): String = when (code) {
        0 -> "\u6674\u6717"
        1 -> "\u6674\u4e91"
        2 -> "\u591a\u4e91"
        3 -> "\u9690\u9634"
        45, 48 -> "\u96fe"
        51, 53, 55 -> "\u6bdb\u6bdb\u96e8"
        56, 57 -> "\u51bb\u96e8"
        61, 63, 65 -> "\u5c0f\u5230\u4e2d\u96e8"
        66, 67 -> "\u51bb\u96e8"
        71, 73, 75 -> "\u5c0f\u5230\u4e2d\u96ea"
        77 -> "\u96ea\u7c92"
        80, 81, 82 -> "\u9635\u96e8"
        85, 86 -> "\u9635\u96ea"
        95 -> "\u96f7\u66b4\u96e8"
        96, 99 -> "\u96f7\u66b4\u96e8\u4f34\u51b0\u96f9"
        else -> "\u672a\u77e5"
    }

    private fun openMeteoEmoji(code: Int): String = when (code) {
        0 -> "\u2600\ufe0f"
        1 -> "\uD83C\uDF24\ufe0f"
        2, 3 -> "\u2601\ufe0f"
        45, 48 -> "\uD83C\uDF2B\ufe0f"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "\uD83C\uDF27\ufe0f"
        71, 73, 75, 77, 85, 86 -> "\u2744\ufe0f"
        95, 96, 99 -> "\u26c8\ufe0f"
        else -> "\u2601"
    }


    /** parse weather JSON (wttr.in j1 + legacy 03c3 formats) */
    private fun parseWeatherJson(json: String, city: String): WeatherData {
        val root = org.json.JSONObject(json)
        if (root.has("current_condition")) {
            val cur = root.optJSONArray("current_condition")?.optJSONObject(0)
            val today = root.optJSONArray("weather")?.optJSONObject(0)
            val area = root.optJSONArray("nearest_area")?.optJSONObject(0)
            val areaName = area?.optJSONArray("areaName")?.optJSONObject(0)?.optString("value", "")
            val desc = cur?.optJSONArray("lang_zh")?.optJSONObject(0)?.optString("value", "")?.takeIf { it.isNotBlank() }
                ?: cur?.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value", "")
                ?: ""
            return WeatherData(
                city = areaName?.takeIf { it.isNotBlank() } ?: city,
                temperature = cur?.optString("temp_C", "--") ?: "--",
                feelsLike = cur?.optString("FeelsLikeC", "--") ?: "--",
                tempHigh = today?.optString("maxtempC", "--") ?: "--",
                tempLow = today?.optString("mintempC", "--") ?: "--",
                humidity = (cur?.optString("humidity", "--") ?: "--"),
                windSpeed = (cur?.optString("windspeedKmph", "--") ?: "--"),
                weather = desc,
                updateTime = cur?.optString("observation_time", "") ?: "",
                weatherIcon = weatherEmoji(cur?.optString("weatherCode", "") ?: "")
            )
        }
        // Open-Meteo 格式（自定义 API 直接填 Open-Meteo URL 时按此解析，兼容 humidity/wind 字段）
        val curOM = root.optJSONObject("current")
        if (curOM != null && curOM.has("temperature_2m")) {
            val daily = root.optJSONObject("daily")
            val maxArr = daily?.optJSONArray("temperature_2m_max")
            val minArr = daily?.optJSONArray("temperature_2m_min")
            val code = curOM.optInt("weather_code", -1)
            return WeatherData(
                city = city,
                temperature = omTemp(curOM.optDouble("temperature_2m", Double.NaN)),
                feelsLike = if (curOM.has("apparent_temperature")) omTemp(curOM.optDouble("apparent_temperature", Double.NaN)) else "",
                tempHigh = omTemp(if (maxArr != null && maxArr.length() > 0) maxArr.optDouble(0, Double.NaN) else Double.NaN),
                tempLow = omTemp(if (minArr != null && minArr.length() > 0) minArr.optDouble(0, Double.NaN) else Double.NaN),
                humidity = curOM.optString("relative_humidity_2m", "--"),
                windSpeed = curOM.optString("wind_speed_10m", "--") + " km/h",
                weather = openMeteoDesc(code),
                updateTime = try { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) } catch (_: Throwable) { "" },
                weatherIcon = openMeteoEmoji(code)
            )
        }
        val data = root.optJSONObject("data") ?: root
        val type = data.optString("type", "")
        return WeatherData(
            city = data.optString("city", city),
            temperature = data.optString("wendu", "--"),
            feelsLike = data.optString("ganmao", "--"),
            tempHigh = data.optString("high", "--"),
            tempLow = data.optString("low", "--"),
            humidity = data.optString("shidu", "--"),
            windSpeed = data.optString("fengli", "--"),
            weather = type,
            updateTime = try { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) } catch (_: Throwable) { "" },
            weatherIcon = weatherEmojiForType(type)
        )
    }

    private fun weatherEmoji(code: String): String = when (code) {
        "113" -> "\u2600\ufe0f"
        "116" -> "\uD83C\uDF24\ufe0f"
        "119", "122", "143" -> "\u2601\ufe0f"
        "176", "263", "266", "293", "296", "299", "302", "305", "308", "353", "356", "359" -> "\uD83C\uDF27\ufe0f"
        "179", "182", "185", "227", "230", "320", "323", "326", "329", "332", "335", "338", "368", "371", "374", "377" -> "\u2744\ufe0f"
        "200", "386", "389", "392", "395" -> "\u26c8\ufe0f"
        "248", "260" -> "\uD83C\uDF2B\ufe0f"
        else -> "\u2601"
    }

    private fun weatherEmojiForType(type: String): String = when {
        type.contains("\u6674") -> "\u2600\ufe0f"
        type.contains("\u96f7") -> "\u26c8\ufe0f"
        type.contains("\u96ea") -> "\u2744\ufe0f"
        type.contains("\u96e8") -> "\uD83C\uDF27\ufe0f"
        type.contains("\u96fe") -> "\uD83C\uDF2B\ufe0f"
        type.contains("\u4e91") || type.contains("\u9634") -> "\u2601\ufe0f"
        else -> "\u2601"
    }

    /** 签名/语录文案（3 模式分发） */
    private fun fetchQuoteText(): String {
        return try {
            when (quoteMode) {
                0 -> fetchWeChatSignature()  // 微信原生签名
                1 -> quoteManual.ifBlank { quoteFallback }  // 手动
                2 -> {  // API+Key (cached within refresh interval)
                    val nowQ = System.currentTimeMillis()
                    val cachedQ = quoteCacheText
                    if (cachedQ.isNotBlank() && nowQ - quoteCacheTime < quoteRefreshInterval * 1000L) cachedQ
                    else {
                        var url = quoteApiUrl
                        if (url.isBlank()) quoteFallback
                        else {
                            if (url.contains(DEAD_API_DOMAIN)) url = DEFAULT_QUOTE_API_URL
                            val json = httpGet(url, quoteApiKey) ?: cachedQ.ifBlank { quoteFallback }
                            val q = try {
                                val obj = org.json.JSONObject(json)
                                obj.optString("data", "").takeIf { it.isNotBlank() }
                                    ?: obj.optString("hitokoto", "").takeIf { it.isNotBlank() }
                                    ?: obj.optString("content", "").takeIf { it.isNotBlank() }
                                    ?: quoteFallback
                            } catch (_: Throwable) {
                                json.trim().takeIf { it.isNotBlank() && !it.startsWith("<") } ?: quoteFallback
                            }
                            quoteCacheText = q
                            quoteCacheTime = nowQ
                            q
                        }
                    }
                }
                else -> quoteFallback
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "fetchQuoteText 失败", e)
            quoteFallback
        }
    }

    /** 每日一言文案（API+Key，异常兜底） */
    /** daily quote text (API+Key, persistent cache within interval, fallback on error) */
    private fun fetchDailyQuote(): String {
        var url = dailyQuoteApiUrl
        if (url.isBlank()) return dailyQuoteFallback
        val now = System.currentTimeMillis()
        val cached = dailyQuoteCacheText
        if (cached.isNotBlank() && now - dailyQuoteCacheTime < dailyQuoteRefreshInterval * 1000L) return cached
        return try {
            if (url.contains(DEAD_API_DOMAIN)) url = DEFAULT_QUOTE_API_URL
            val json = httpGet(url, dailyQuoteApiKey) ?: return cached.ifBlank { dailyQuoteFallback }
            val q = try {
                val obj = org.json.JSONObject(json)
                obj.optString("data", "").takeIf { it.isNotBlank() }
                    ?: obj.optString("hitokoto", "").takeIf { it.isNotBlank() }
                    ?: obj.optString("content", "").takeIf { it.isNotBlank() }
                    ?: dailyQuoteFallback
            } catch (_: Throwable) {
                json.trim().takeIf { it.isNotBlank() && !it.startsWith("<") } ?: dailyQuoteFallback
            }
            dailyQuoteCacheText = q
            dailyQuoteCacheTime = now
            q
        } catch (e: Throwable) {
            WeLogger.e(TAG, "fetchDailyQuote failed", e)
            cached.ifBlank { dailyQuoteFallback }
        }
    }


    /** 微信个人签名（反射获取，缓存 1 小时） */
    private fun fetchWeChatSignature(): String {
        val now = System.currentTimeMillis()
        if (cachedWeChatSignature.isNotBlank() && now - lastSignatureFetchTime < quoteRefreshInterval * 1000L) return cachedWeChatSignature
        return try {
            val selfWxId = WeApi.selfWxId
            if (selfWxId.isBlank()) return quoteFallback
            // 从 WeDatabaseApi 获取个人签名（不同版本字段可能不同）
            val profile: String? = try {
                // 尝试 profile / signature / description 等字段
                listOf("signature", "description", "profile", "bio", "motto").firstNotNullOfOrNull { fn ->
                    try {
                        WeDatabaseApi::class.java.getDeclaredMethod("get${fn.replaceFirstChar { it.uppercase() }}").apply { isAccessible = true }
                            .invoke(WeDatabaseApi) as? String
                    } catch (_: Throwable) { null }
                }
            } catch (_: Throwable) { null }
            val sig = profile?.takeIf { it.isNotBlank() } ?: quoteFallback
            cachedWeChatSignature = sig; lastSignatureFetchTime = now
            sig
        } catch (e: Throwable) {
            WeLogger.e(TAG, "fetchWeChatSignature 失败", e)
            quoteFallback
        }
    }

    /** HTTP GET 请求（10s 超时 + 异常捕获） */
    private fun httpGet(urlStr: String, apiKey: String = ""): String? {
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        return try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("X-API-Key", apiKey)
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else { input = connection.inputStream; input.bufferedReader().readText() }
        } catch (e: Throwable) {
            WeLogger.w(TAG, "httpGet 失败: ${e.message}")
            null
        } finally {
            try { input?.close() } catch (_: Throwable) {}
            try { connection?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** 头像加载（按 avatarMode 分发 3 模式） */
    private fun loadAvatarBitmap(act: Activity): Bitmap? {
        return try {
            when (avatarMode) {
                1 -> {  // 本地图片
                    val path = avatarLocalPath
                    if (path.isBlank()) loadWeChatAvatar(act)
                    else {
                        val f = File(path)
                        if (f.exists() && f.canRead()) decodeScaledBitmapFile(f, MAX_AVATAR_SIZE) else loadWeChatAvatar(act)
                    }
                }
                2 -> {  // 网络 URL
                    val url = avatarUrl
                    if (url.isBlank()) loadWeChatAvatar(act)
                    else downloadBitmap(url) ?: loadWeChatAvatar(act)
                }
                else -> loadWeChatAvatar(act)  // 模式 0 = 自动微信头像
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "loadAvatarBitmap 失败", e)
            null
        }
    }

    /** 按目标尺寸缩小解码本地文件（头像仅显示 56dp，避免原图大图解码慢/占内存） */
    private fun decodeScaledBitmapFile(f: File, maxSize: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= maxSize && opts.outHeight / (sample * 2) >= maxSize) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Throwable) {
        WeLogger.w(TAG, "decodeScaledBitmapFile 失败", e)
        null
    }

    /** 按目标尺寸缩小解码字节数组（网络头像） */
    private fun decodeScaledBitmapBytes(bytes: ByteArray, maxSize: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= maxSize && opts.outHeight / (sample * 2) >= maxSize) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Throwable) {
        WeLogger.w(TAG, "decodeScaledBitmapBytes 失败", e)
        null
    }

    private fun getAvatarCacheFile(act: Activity): File {
        val dir = File(act.cacheDir, AVATAR_CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "avatar_cache.png")
    }

    /** 微信本人头像加载（CDN URL 策略 + 缓存） */
    private fun loadWeChatAvatar(act: Activity): Bitmap? {
        val cacheFile = getAvatarCacheFile(act)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                val bmp = decodeScaledBitmapFile(cacheFile, MAX_AVATAR_SIZE)
                if (bmp != null) return bmp
            } catch (e: Throwable) { WeLogger.w(TAG, "头像缓存读取失败", e) }
        }
        return try {
            var selfWxId = WeApi.selfWxId
            var waited = 0
            while (selfWxId.isBlank() && waited < 4) {
                try { Thread.sleep(1000) } catch (_: Throwable) {}
                selfWxId = WeApi.selfWxId
                waited++
            }
            if (selfWxId.isNotEmpty()) {
                var avatarUrl = WeDatabaseApi.getAvatarUrl(selfWxId)
                if (avatarUrl.isBlank()) avatarUrl = queryContactAvatarFallback(selfWxId)
                if (avatarUrl.isNotEmpty()) {
                    val bmp = downloadBitmap(avatarUrl)
                    if (bmp != null) {
                        try { cacheFile.writeBytes(bitmapToBytes(bmp)) } catch (_: Throwable) {}
                    }
                    bmp
                } else null
            } else null
        } catch (e: Throwable) {
            WeLogger.e(TAG, "loadWeChatAvatar 失败", e)
            null
        }
    }

    /** fallback: read avatar URL from rcontact table */
    private fun queryContactAvatarFallback(wxid: String): String = try {
        val rows = WeDatabaseApi.executeQuery(
            "SELECT COALESCE(bigHeadImgUrl, smallHeadImgUrl, avatarUrl, '') AS u FROM rcontact WHERE username='" + wxid.replace("'", "''") + "'"
        )
        if (rows.isNotEmpty()) (rows[0]["u"]?.toString() ?: "") else ""
    } catch (_: Throwable) { "" }

    private fun bitmapToBytes(bmp: Bitmap): ByteArray = try {
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        baos.toByteArray()
    } catch (e: Throwable) { ByteArray(0) }

    private fun loadWeChatNickname(act: Activity): String = try {
        val selfWxId = WeApi.selfWxId
        if (selfWxId.isNotEmpty()) WeDatabaseApi.getDisplayName(selfWxId) else ""
    } catch (e: Throwable) { "" }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        return try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 10000; doInput = true
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else {
                input = connection.inputStream
                decodeScaledBitmapBytes(input.readBytes(), MAX_AVATAR_SIZE)
            }
        } catch (e: Throwable) {
            WeLogger.w(TAG, "downloadBitmap 失败: ${e.message}")
            null
        } finally {
            try { input?.close() } catch (_: Throwable) {}
            try { connection?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun startActivityByName(context: Context, className: String, isCustomIntent: Boolean = false) {
        if (className.isBlank()) return
        try {
            val intent = Intent().apply {
                if (isCustomIntent) setClassName(context.packageName, className)
                else setClassName(context.packageName, className)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "启动Activity失败: $className", e)
            showToast("无法打开该功能")
        }
    }

    // ==================== 设置入口（首选项页面）====================
    override fun onClick(context: ComponentActivity) {
        if (!BuildConfig.BEAUTIFY_ENABLED) {
            showToast("侧边栏功能编译开关已关闭"); return
        }
        try {
            showComposeDialog(context) {
                var localEnable by remember { mutableStateOf(masterEnabled) }
                val initMode = remember { migratedTriggerMode() }
                var localFull by remember { mutableStateOf((initMode and MODE_FULL_SWIPE) != 0) }
                var localEdge by remember { mutableStateOf((initMode and MODE_EDGE_STRIP) != 0) }
                var localButton by remember { mutableStateOf((initMode and MODE_TRIGGER_BUTTON) != 0) }
                var localEdgeWidth by remember { mutableStateOf(edgeZoneWidthDp.toFloat()) }
                var localSideMode by remember { mutableStateOf(sidePanelMode) }

                AlertDialogContent(
                    title = { Text("微信主页侧边栏") },
                    text = {
                        DefaultColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            ListItem(
                                modifier = Modifier.clickable { localEnable = !localEnable },
                                trailingContent = { androidx.compose.material3.Switch(localEnable, null) },
                                headlineContent = { Text("启用侧边栏") }
                            )
                            Text(
                                "打开方式",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                            ListItem(
                                modifier = Modifier.clickable { localFull = !localFull },
                                trailingContent = { androidx.compose.material3.Switch(localFull, null) },
                                headlineContent = { Text("全屏右滑跟手") }
                            )
                            ListItem(
                                modifier = Modifier.clickable { localEdge = !localEdge },
                                trailingContent = { androidx.compose.material3.Switch(localEdge, null) },
                                headlineContent = { Text("左边缘右滑") }
                            )
                            ListItem(
                                modifier = Modifier.clickable { localButton = !localButton },
                                trailingContent = { androidx.compose.material3.Switch(localButton, null) },
                                headlineContent = { Text("左上角按钮") }
                            )
                            if (localEdge) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "左边缘触摸条宽度 ${localEdgeWidth.toInt()}dp",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Slider(
                                        value = localEdgeWidth,
                                        onValueChange = {
                                            localEdgeWidth = it
                                            edgeZoneWidthDp = it.toInt().coerceIn(10, 80)
                                        },
                                        valueRange = 10f..80f,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Text(
                                "侧边栏实现",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                            ListItem(
                                modifier = Modifier.clickable { localSideMode = if (localSideMode == 1) 2 else 1 },
                                trailingContent = { Text(if (localSideMode == 1) "方式一 · 原版侧边栏" else "方式二 · WeKit 侧边栏", fontSize = 12.sp) },
                                headlineContent = { Text("侧边栏实现方式") }
                            )
                            Text(
                                "方式一：本模块原有侧边栏（头像/签名/天气/每日一言/快捷功能槽）。\n方式二：WeKit 侧边栏全家桶（拖拽编辑/卡片/农历/一言/天气/钱包等，功能更完整）。\n切换后需重启微信生效；本开关已合并原「主页侧滑面板」，将按所选方式启用对应实现。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            Text(
                                "说明",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                            Text(
                                "开启后通过所选方式打开侧滑面板（三种方式可自由组合）。\n全屏右滑跟手：在主界面任意位置按住向右拖动，面板即跟随手指呼出。\n左边缘右滑：在主界面最左边缘按住向右滑动呼出（状态栏下方整条左缘），触摸条宽度可调。\n面板内可配置：头像/签名/天气/每日一言/快捷功能 4 槽。\n长按对应卡片可触发配置弹窗。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    },
                    dismissButton = { TextButton(onDismiss) { Text("取消") } },
                    confirmButton = {
                        Button(onClick = {
                            if (!localFull && !localEdge && !localButton) {
                                showToast("请至少选择一种打开方式")
                                return@Button
                            }
                            val newMode = (if (localFull) MODE_FULL_SWIPE else 0) or
                                (if (localEdge) MODE_EDGE_STRIP else 0) or
                                (if (localButton) MODE_TRIGGER_BUTTON else 0)
                            try {
                                if (sidePanelMode != localSideMode) {
                                    sidePanelMode = localSideMode
                                }
                                if (masterEnabled != localEnable) {
                                    masterEnabled = localEnable
                                    if (localEnable) onEnable() else onDisable()
                                }
                                if (migratedTriggerMode() != newMode) {
                                    triggerMode = newMode
                                    if (masterEnabled) {
                                        // 重新按新打开方式挂载触发视图
                                        removeAllViews()
                                        attachedActivity?.let { updateVisibility() }
                                    }
                                }
                                if (localEnable && (newMode and MODE_FULL_SWIPE) != 0 && !gestureHookInstalled) {
                                    attachedActivity?.let { attachEdgeZone(it) }
                                }
                                if (localEnable && (newMode and MODE_EDGE_STRIP) != 0 && edgeZoneStripView == null) {
                                    attachedActivity?.let { attachEdgeZoneStrip(it) }
                                }
                                if (localEnable && (newMode and MODE_TRIGGER_BUTTON) != 0 && triggerButtonView == null) {
                                    attachedActivity?.let { attachTriggerButton(it) }
                                }
                            } catch (e: Throwable) { WeLogger.e(TAG, "保存开关异常", e) }
                            onDismiss()
                        }) { Text("保存") }
                    }
                )
            }
        } catch (e: Throwable) { WeLogger.e(TAG, "onClick 异常", e) }
    }

    // ==================== 常量 ====================
    private const val TAG = "HomeSidePanel"
    private const val PREFS_PREFIX = "hsp_"
    private const val AVATAR_CACHE_DIR = "home_side_panel"
    private const val MAX_AVATAR_SIZE = 192  // 头像仅显示 56dp，按 3x 密度解码，避免原图解码/缓存慢
    private const val DEAD_API_DOMAIN = "03c3.cn"
    private const val DEFAULT_WEATHER_API_URL = "https://wttr.in/{city}?format=j1&lang=zh"
    private const val DEFAULT_QUOTE_API_URL = "https://v1.hitokoto.cn/"
}
