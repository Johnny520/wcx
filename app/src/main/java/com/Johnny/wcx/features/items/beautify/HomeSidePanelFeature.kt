package com.Johnny.wcx.features.items.beautify

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import com.Johnny.wcx.constants.PackageNames
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Cloud
import com.composables.icons.materialsymbols.outlined.Collections_bookmark
import com.composables.icons.materialsymbols.outlined.Favorite
import com.composables.icons.materialsymbols.outlined.Play_circle
import com.composables.icons.materialsymbols.outlined.Qr_code_scanner
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Wallet
import com.composables.icons.materialsymbols.outlinedfilled.Add
import com.composables.icons.materialsymbols.outlinedfilled.Check_circle
import com.composables.icons.materialsymbols.outlinedfilled.Delete
import com.composables.icons.materialsymbols.outlinedfilled.Drag_handle
import com.composables.icons.materialsymbols.outlinedfilled.Edit
import com.composables.icons.materialsymbols.outlinedfilled.Menu
import com.composables.icons.materialsymbols.outlinedfilled.Menu_open
import com.composables.icons.materialsymbols.outlinedfilled.More_vert
import com.composables.icons.materialsymbols.outlinedfilled.Person
import com.composables.icons.materialsymbols.outlinedfilled.Refresh
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.SelfProfileField
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.utils.InjectedUiTheme
import com.Johnny.wcx.ui.utils.LifecycleOwnerProvider
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import dev.ujhhgtg.reflekt.firstField
import dev.ujhhgtg.reflekt.firstMethod
import dev.ujhhgtg.reflekt.reflekt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "HomeSidePanel"
private const val PREFS_PREFIX = "home_side_panel_"
private const val AVATAR_CACHE_DIR = "home_side_panel_avatar"

/**
 * 微信主页侧滑侧边栏功能（WindowManager 版本）
 * 在微信首页左侧添加侧滑面板，包含天气、快捷按钮、功能跳转等
 *
 * 视图策略：
 * - 触发按钮：TYPE_APPLICATION_OVERLAY 悬浮窗，始终显示（LauncherUI 可见时）
 * - 面板 ComposeView：TYPE_APPLICATION_OVERLAY 全屏悬浮窗，通过 WindowManager.addView/removeView 切换显示
 * - 不 Hook Activity.onResume，使用轮询方式检测 LauncherUI 可见性
 * - 不修改微信原生视图树
 */
@Feature(
    name = "微信主页侧滑侧边栏",
    categories = ["界面美化"],
    description = "在微信主页左侧添加侧滑面板，包含天气、快捷按钮、功能跳转、每日一言等模块"
)
object HomeSidePanelFeature : ClickableFeature() {

    override val alwaysEnabled = false
    override val noSwitchWidget = false

    // ==================== 视图引用 ====================

    @Volatile
    private var triggerView: View? = null
    @Volatile
    private var panelComposeView: View? = null

    private var windowManager: WindowManager? = null

    // ==================== 轮询 ====================

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!masterEnabled) return
            checkAndAttach()
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }
    private var polling = false

    // ==================== 配置属性 ====================

    private var masterEnabled by WePrefs.prefOption("${PREFS_PREFIX}master", false)

    var headerEnabled by WePrefs.prefOption("${PREFS_PREFIX}header_enabled", true)
    var weatherEnabled by WePrefs.prefOption("${PREFS_PREFIX}weather_enabled", true)
    var quickButtonsEnabled by WePrefs.prefOption("${PREFS_PREFIX}quick_buttons_enabled", true)
    var momentsEntryEnabled by WePrefs.prefOption("${PREFS_PREFIX}moments_entry", true)
    var videoEntryEnabled by WePrefs.prefOption("${PREFS_PREFIX}video_entry", true)
    var clearUnreadEnabled by WePrefs.prefOption("${PREFS_PREFIX}clear_unread", true)
    var wcxSettingsEnabled by WePrefs.prefOption("${PREFS_PREFIX}wcx_settings", true)
    var dailyQuoteEnabled by WePrefs.prefOption("${PREFS_PREFIX}daily_quote", true)

    var onlineStatus by WePrefs.prefOption("${PREFS_PREFIX}online_status", "在线")
    var isOnline by WePrefs.prefOption("${PREFS_PREFIX}is_online", true)

    var useSignature by WePrefs.prefOption("${PREFS_PREFIX}use_signature", false)
    var quoteApiUrl by WePrefs.prefOption("${PREFS_PREFIX}quote_api_url", "https://api.03c3.cn/api/yl")
    var quoteApiKey by WePrefs.prefOption("${PREFS_PREFIX}quote_api_key", "")
    var quoteRefreshInterval by WePrefs.prefOption("${PREFS_PREFIX}quote_refresh_interval", 3600)
    var quoteFallback by WePrefs.prefOption("${PREFS_PREFIX}quote_fallback", "每一天都是新的开始")

    var weatherCity by WePrefs.prefOption("${PREFS_PREFIX}weather_city", "北京")
    var weatherApiUrl by WePrefs.prefOption("${PREFS_PREFIX}weather_api_url", "https://api.03c3.cn/api/weather")
    var weatherApiKey by WePrefs.prefOption("${PREFS_PREFIX}weather_api_key", "")
    var weatherRefreshInterval by WePrefs.prefOption("${PREFS_PREFIX}weather_refresh_interval", 1800)
    var weatherFallbackCity by WePrefs.prefOption("${PREFS_PREFIX}weather_fallback_city", "北京")

    var dailyQuoteApiUrl by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_api_url", "https://api.03c3.cn/api/yl")
    var dailyQuoteApiKey by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_api_key", "")
    var dailyQuoteRefreshInterval by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_refresh_interval", 3600)
    var dailyQuoteFallback by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_fallback", "生活不止眼前的苟且，还有诗和远方")

    @Serializable
    data class QuickButtonConfig(
        val id: String = UUID.randomUUID().toString(),
        val name: String = "",
        val iconName: String = "Add",
        val targetActivity: String = ""
    )

    private val defaultQuickButtons = listOf(
        QuickButtonConfig("scan", "扫一扫", "Qr_code_scanner", "com.tencent.mm.plugin.scanner.ui.BaseScanUI"),
        QuickButtonConfig("pay", "收付款", "Wallet", "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI"),
        QuickButtonConfig("fav", "收藏", "Collections_bookmark", "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI"),
        QuickButtonConfig("moments", "朋友圈", "Favorite", "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI")
    )

    var quickButtonsJson by WePrefs.prefOption("${PREFS_PREFIX}quick_buttons_json", "")

    private fun loadQuickButtons(): List<QuickButtonConfig> {
        val json = quickButtonsJson
        if (json.isBlank()) return defaultQuickButtons
        return try { Json.decodeFromString<List<QuickButtonConfig>>(json) } catch (e: Exception) {
            WeLogger.e(TAG, "解析快捷按钮配置失败", e); defaultQuickButtons
        }
    }

    private fun saveQuickButtons(list: List<QuickButtonConfig>) {
        quickButtonsJson = Json.encodeToString(list)
    }

    @Serializable
    data class CustomFeature(
        val id: String = UUID.randomUUID().toString(),
        val name: String = "",
        val iconName: String = "Add",
        val targetActivity: String = "",
        val isCustomIntent: Boolean = false
    )

    var customFeaturesJson by WePrefs.prefOption("${PREFS_PREFIX}custom_features_json", "")

    private fun loadCustomFeatures(): List<CustomFeature> {
        val json = customFeaturesJson
        if (json.isBlank()) return emptyList()
        return try { Json.decodeFromString<List<CustomFeature>>(json) } catch (e: Exception) {
            WeLogger.e(TAG, "解析自定义功能配置失败", e); emptyList()
        }
    }

    private fun saveCustomFeatures(list: List<CustomFeature>) {
        customFeaturesJson = Json.encodeToString(list)
    }

    // ==================== 图标映射 ====================

    private val iconPool = mapOf(
        "Qr_code_scanner" to MaterialSymbols.Outlined.Qr_code_scanner,
        "Wallet" to MaterialSymbols.Outlined.Wallet,
        "Collections_bookmark" to MaterialSymbols.Outlined.Collections_bookmark,
        "Favorite" to MaterialSymbols.Outlined.Favorite,
        "Play_circle" to MaterialSymbols.Outlined.Play_circle,
        "Settings" to MaterialSymbols.Outlined.Settings,
        "Add" to MaterialSymbols.OutlinedFilled.Add,
        "Check_circle" to MaterialSymbols.OutlinedFilled.Check_circle,
        "Refresh" to MaterialSymbols.OutlinedFilled.Refresh,
        "Person" to MaterialSymbols.OutlinedFilled.Person,
        "Edit" to MaterialSymbols.OutlinedFilled.Edit,
        "Delete" to MaterialSymbols.OutlinedFilled.Delete,
        "Drag_handle" to MaterialSymbols.OutlinedFilled.Drag_handle,
        "Cloud" to MaterialSymbols.Outlined.Cloud
    )

    private val presetTargets = mapOf(
        "扫一扫" to "com.tencent.mm.plugin.scanner.ui.BaseScanUI",
        "朋友圈" to "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI",
        "视频号" to "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI",
        "钱包" to "com.tencent.mm.plugin.mall.ui.MallIndexUIv2",
        "收藏夹" to "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI",
        "设置" to "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI",
        "小程序" to "com.tencent.mm.plugin.appbrand.ui.AppBrandLauncherUI",
        "卡包" to "com.tencent.mm.plugin.card.ui.CardIndexUI",
        "表情" to "com.tencent.mm.plugin.emoji.ui.v2.EmojiStoreV2UI",
        "游戏" to "com.tencent.mm.plugin.game.ui.GameCenterUI",
        "搜一搜" to "com.tencent.mm.plugin.fts.ui.FTSMainUI",
        "看一看" to "com.tencent.mm.plugin.topstory.ui.TopStoryUI",
        "直播" to "com.tencent.mm.plugin.finder.live.viewmodel.FinderLiveHomeUI"
    )

    // ==================== 天气缓存 ====================

    @Serializable
    data class WeatherData(
        val city: String = "",
        val temperature: String = "",
        val feelsLike: String = "",
        val tempHigh: String = "",
        val tempLow: String = "",
        val humidity: String = "",
        val windSpeed: String = "",
        val weather: String = "",
        val updateTime: String = "",
        val weatherIcon: String = ""
    )

    private var cachedWeather: WeatherData? = null
    private var lastWeatherFetchTime = 0L

    // ==================== 生命周期 ====================

    override fun onEnable() {
        if (!BuildConfig.BEAUTIFY_ENABLED) {
            WeLogger.w(TAG, "侧边栏功能编译开关已关闭，跳过启用")
            return
        }
        masterEnabled = true
        WeLogger.i(TAG, "侧边栏功能已启用")
        startPolling()
    }

    override fun onDisable() {
        masterEnabled = false
        WeLogger.i(TAG, "侧边栏功能已关闭")
        stopPolling()
        removeAllViews()
    }

    // ==================== 轮询检测 ====================

    private fun startPolling() {
        if (polling) return
        polling = true
        mainHandler.post(pollRunnable)
        WeLogger.d(TAG, "轮询已启动")
    }

    private fun stopPolling() {
        polling = false
        mainHandler.removeCallbacks(pollRunnable)
        WeLogger.d(TAG, "轮询已停止")
    }

    private fun checkAndAttach() {
        if (!masterEnabled) return
        try {
            val launcherActivity = findLauncherUIActivity() ?: run {
                removeAllViews()
                return
            }
            if (triggerView == null) {
                attachTriggerButton(launcherActivity)
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "轮询检测异常", e)
        }
    }

    /**
     * 通过反射查找当前可见的 LauncherUI Activity。
     */
    private fun findLauncherUIActivity(): Activity? {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAtMethod = atClass.getDeclaredMethod("currentActivityThread")
            val at = currentAtMethod.invoke(null)
            val activitiesField = atClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(at) as? Map<*, *> ?: return null

            for (record in activities.values) {
                val activity = record?.javaClass?.getDeclaredField("activity")
                    ?.apply { isAccessible = true }
                    ?.get(record) as? Activity ?: continue
                if (activity.javaClass.name == "com.tencent.mm.ui.LauncherUI" && !activity.isFinishing) {
                    return activity
                }
            }
        } catch (e: Throwable) {
            WeLogger.d(TAG, "查找 LauncherUI 失败: ${e.message}")
        }
        return null
    }

    // ==================== WindowManager 视图管理 ====================

    private fun getWindowManager(act: Activity): WindowManager {
        if (windowManager != null) return windowManager!!
        val wm = act.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        return wm
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTriggerButton(act: Activity) {
        if (triggerView != null) return

        val wm = getWindowManager(act)
        val d = act.resources.displayMetrics.density
        val statusBarH = getStatusBarHeight(act)
        val isDark = (act.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val triggerSize = (40 * d).toInt()
        val bgColor = if (isDark) AndroidColor.parseColor("#2C2C2C") else AndroidColor.WHITE
        val accentColor = if (isDark) AndroidColor.parseColor("#64B5F6") else AndroidColor.parseColor("#1976D2")

        val trigger = FrameLayout(act).apply {
            tag = "home_side_panel_trigger"
            layoutParams = FrameLayout.LayoutParams(triggerSize, triggerSize)
            background = GradientDrawable().apply {
                setColor(bgColor)
                alpha = 230
                shape = GradientDrawable.OVAL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 4f * d
            }
            setOnClickListener { showPanel(act) }
            addView(ImageView(act).apply {
                setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(accentColor)
                    setSize((16 * d).toInt(), (2 * d).toInt())
                }
                setImageDrawable(drawable)
                layoutParams = FrameLayout.LayoutParams(
                    (22 * d).toInt(), (22 * d).toInt(), Gravity.CENTER
                )
            })
        }.also { triggerView = it }

        val params = WindowManager.LayoutParams(
            triggerSize,
            triggerSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * d).toInt()
            y = statusBarH + (8 * d).toInt()
        }

        wm.addView(trigger, params)
        WeLogger.d(TAG, "触发按钮已添加到 WindowManager")
    }

    private fun showPanel(act: Activity) {
        if (panelComposeView != null) return

        val wm = getWindowManager(act)
        val lifecycleOwner = LifecycleOwnerProvider.getOrCreate(act)

        val composeView = ComposeView(act).apply {
            tag = "home_side_panel_overlay"
            setContent {
                InjectedUiTheme {
                    SidePanelOverlay(act)
                }
            }
            // 绑定生命周期
            lifecycleOwner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    removePanel()
                }
            })
        }.also { panelComposeView = it }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        wm.addView(composeView, params)

        // 隐藏触发按钮
        triggerView?.let { wm.removeView(it) }
        triggerView = null
        WeLogger.d(TAG, "侧边栏面板已展开")
    }

    private fun removePanel() {
        val panel = panelComposeView ?: return
        panelComposeView = null

        try {
            windowManager?.removeView(panel)
        } catch (e: Throwable) {
            WeLogger.w(TAG, "移除面板失败", e)
        }
        WeLogger.d(TAG, "侧边栏面板已收起")
    }

    private fun hidePanel() {
        removePanel()
        // 重新显示触发按钮（需要找到当前 LauncherUI）
        val act = findLauncherUIActivity()
        if (act != null) {
            attachTriggerButton(act)
        }
    }

    private fun removeAllViews() {
        panelComposeView?.let {
            try { windowManager?.removeView(it) } catch (_: Throwable) {}
            panelComposeView = null
        }
        triggerView?.let {
            try { windowManager?.removeView(it) } catch (_: Throwable) {}
            triggerView = null
        }
    }

    private fun getStatusBarHeight(act: Activity): Int {
        return try {
            val resourceId = act.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) act.resources.getDimensionPixelSize(resourceId) else 0
        } catch (e: Throwable) { 0 }
    }

    // ==================== 侧边栏主界面 ====================

    @Composable
    private fun SidePanelOverlay(act: Activity) {
        var showSettings by remember { mutableStateOf(false) }
        var showWeatherSettings by remember { mutableStateOf(false) }
        val isDark = isSystemInDarkTheme()

        val bgColor = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)
        val cardBgColor = if (isDark) Color(0xFF2C2C2C) else Color.White
        val textColor = if (isDark) Color(0xFFE0E0E0) else Color(0xFF212121)
        val subTextColor = if (isDark) Color(0xFFAAAAAA) else Color(0xFF999999)
        val accentColor = if (isDark) Color(0xFF64B5F6) else Color(0xFF1976D2)

        Box(modifier = Modifier.fillMaxSize()) {
            // 遮罩层
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { hidePanel() }
            )

            // 侧边栏面板
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.65f)
                    .widthIn(max = 320.dp),
                color = bgColor,
                shadowElevation = 16.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("侧边栏", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, color = textColor)
                        Row {
                            IconButton(onClick = { showSettings = true }) {
                                Icon(MaterialSymbols.Outlined.Settings,
                                    contentDescription = "设置", tint = accentColor)
                            }
                            IconButton(onClick = { hidePanel() }) {
                                Icon(MaterialSymbols.OutlinedFilled.Menu_open,
                                    contentDescription = "关闭", tint = subTextColor)
                            }
                        }
                    }

                    HorizontalDivider(color = if (isDark) Color(0xFF3A3A3A) else Color(0xFFE0E0E0))

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (headerEnabled) {
                            item { SidePanelHeader(act, cardBgColor, textColor, subTextColor, accentColor) }
                        }
                        if (weatherEnabled) {
                            item {
                                SidePanelWeatherCard(act, cardBgColor, textColor, subTextColor, accentColor,
                                    onLongClick = { showWeatherSettings = true })
                            }
                        }
                        if (quickButtonsEnabled) {
                            item { SidePanelQuickButtons(act, cardBgColor, textColor, accentColor, subTextColor) }
                        }
                        item {
                            SidePanelFeatureList(act, cardBgColor, textColor, subTextColor, accentColor, isDark)
                        }
                        if (dailyQuoteEnabled) {
                            item { SidePanelDailyQuote(cardBgColor, textColor, subTextColor, accentColor) }
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }
                }
            }
        }

        if (showSettings) {
            SidePanelSettingsDialog(onDismiss = { showSettings = false })
        }
        if (showWeatherSettings) {
            WeatherSettingsDialog(act = act, onDismiss = { showWeatherSettings = false })
        }
    }

    // ==================== 头部区域 ====================

    @Composable
    private fun SidePanelHeader(
        act: Activity, cardBgColor: Color, textColor: Color, subTextColor: Color, accentColor: Color
    ) {
        var showStatusConfig by remember { mutableStateOf(false) }
        var showQuoteConfig by remember { mutableStateOf(false) }
        var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var avatarLoaded by remember { mutableStateOf(false) }
        var nickname by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            if (!avatarLoaded) {
                avatarBitmap = loadWeChatAvatar(act)
                nickname = loadWeChatNickname(act)
                avatarLoaded = true
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { showQuoteConfig = true }),
            colors = CardDefaults.cardColors(containerColor = cardBgColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier.size(56.dp).clip(CircleShape).background(Color(0xFFE0E0E0))
                    ) {
                        if (avatarBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = avatarBitmap!!.asImageBitmap(),
                                contentDescription = "头像",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(MaterialSymbols.OutlinedFilled.Person, contentDescription = null,
                                modifier = Modifier.fillMaxSize().padding(12.dp), tint = subTextColor)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { showStatusConfig = true })
                        ) {
                            Box(
                                modifier = Modifier.size(8.dp).clip(CircleShape)
                                    .background(if (isOnline) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(onlineStatus, fontSize = 13.sp, color = subTextColor)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        if (nickname.isNotBlank()) {
                            Text(nickname, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        val timeStr = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
                        val dateStr = remember { SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE).format(Date()) }
                        Text(timeStr, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = textColor)
                        Text(dateStr, fontSize = 12.sp, color = subTextColor)
                    }
                    IconButton(
                        onClick = {
                            avatarBitmap = refreshAvatar(act)
                            nickname = loadWeChatNickname(act)
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(MaterialSymbols.OutlinedFilled.Refresh, contentDescription = "刷新头像",
                            tint = subTextColor, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                val quoteText = remember { mutableStateOf("") }
                val quoteConfigured = quoteApiUrl.isNotBlank() || useSignature
                LaunchedEffect(Unit) {
                    if (quoteConfigured) quoteText.value = fetchQuoteText()
                }
                Text(
                    if (!quoteConfigured) "未配置API地址" else quoteText.value.ifBlank { "每一天都是新的开始" },
                    fontSize = 13.sp, color = subTextColor, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (showStatusConfig) {
            AlertDialog(
                onDismissRequest = { showStatusConfig = false },
                title = { Text("在线状态配置") },
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("在线"); Switch(checked = isOnline, onCheckedChange = { isOnline = it })
                        }
                        OutlinedTextField(value = onlineStatus, onValueChange = { onlineStatus = it },
                            label = { Text("状态文字") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                },
                confirmButton = { TextButton(onClick = { showStatusConfig = false }) { Text("确定") } }
            )
        }

        if (showQuoteConfig) {
            var localUseSignature by remember { mutableStateOf(useSignature) }
            var localApiUrl by remember { mutableStateOf(quoteApiUrl) }
            var localApiKey by remember { mutableStateOf(quoteApiKey) }
            var localInterval by remember { mutableStateOf(quoteRefreshInterval.toString()) }
            var localFallback by remember { mutableStateOf(quoteFallback) }

            AlertDialog(
                onDismissRequest = { showQuoteConfig = false },
                title = { Text("语录配置") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("使用微信个人签名", modifier = Modifier.weight(1f))
                            Switch(checked = localUseSignature, onCheckedChange = { localUseSignature = it })
                        }
                        if (!localUseSignature) {
                            OutlinedTextField(value = localApiUrl, onValueChange = { localApiUrl = it },
                                label = { Text("API地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(value = localApiKey, onValueChange = { localApiKey = it },
                                label = { Text("API Key (可选)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(value = localInterval, onValueChange = { localInterval = it },
                                label = { Text("刷新间隔(秒)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(value = localFallback, onValueChange = { localFallback = it },
                                label = { Text("兜底语录") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        useSignature = localUseSignature; quoteApiUrl = localApiUrl; quoteApiKey = localApiKey
                        quoteRefreshInterval = localInterval.toIntOrNull() ?: 3600; quoteFallback = localFallback
                        showQuoteConfig = false; showToast("语录配置已保存")
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        useSignature = false; quoteApiUrl = "https://api.03c3.cn/api/yl"; quoteApiKey = ""
                        quoteRefreshInterval = 3600; quoteFallback = "每一天都是新的开始"; showQuoteConfig = false
                    }) { Text("恢复默认") }
                }
            )
        }
    }

    // ==================== 天气卡片 ====================

    @Composable
    private fun SidePanelWeatherCard(
        act: Activity, cardBgColor: Color, textColor: Color, subTextColor: Color, accentColor: Color,
        onLongClick: () -> Unit
    ) {
        val weather = remember { mutableStateOf<WeatherData?>(null) }
        val isConfigured = weatherApiUrl.isNotBlank()
        LaunchedEffect(Unit) {
            if (isConfigured) weather.value = fetchWeatherData()
        }

        val cardAlpha = if (isConfigured) 1f else 0.4f
        Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(
                onClick = {},
                onLongClick = onLongClick
            ),
            colors = CardDefaults.cardColors(containerColor = cardBgColor.copy(alpha = cardAlpha)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val w = weather.value
                if (w != null) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(w.city, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)
                            Text(w.updateTime, fontSize = 11.sp, color = subTextColor)
                        }
                        Icon(MaterialSymbols.Outlined.Cloud, contentDescription = null, tint = accentColor, modifier = Modifier.size(32.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom) {
                        Text(w.temperature, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = textColor)
                        Column(horizontalAlignment = Alignment.End) {
                            Text("体感 ${w.feelsLike}", fontSize = 12.sp, color = subTextColor)
                            Text("${w.tempHigh} / ${w.tempLow}", fontSize = 12.sp, color = subTextColor)
                            Text("湿度 ${w.humidity} 风速 ${w.windSpeed}", fontSize = 11.sp, color = subTextColor)
                        }
                    }
                    if (w.weather.isNotBlank()) Text(w.weather, fontSize = 13.sp, color = accentColor)
                } else if (!isConfigured) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        Icon(MaterialSymbols.Outlined.Cloud, contentDescription = null, tint = subTextColor, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("未配置API地址", color = subTextColor)
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        Icon(MaterialSymbols.Outlined.Cloud, contentDescription = null, tint = subTextColor, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("加载天气中...", color = subTextColor)
                    }
                }
            }
        }
    }

    // ==================== 快捷按钮 ====================

    @Composable
    private fun SidePanelQuickButtons(
        act: Activity, cardBgColor: Color, textColor: Color, accentColor: Color, subTextColor: Color
    ) {
        val buttons = remember { loadQuickButtons() }
        var showConfig by remember { mutableStateOf(false) }
        var editingButtonIndex by remember { mutableIntStateOf(-1) }

        Card(colors = CardDefaults.cardColors(containerColor = cardBgColor), shape = RoundedCornerShape(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                buttons.forEachIndexed { index, btn ->
                    Column(
                        modifier = Modifier.weight(1f)
                            .combinedClickable(
                                onClick = { startActivityByName(act, btn.targetActivity) },
                                onLongClick = { editingButtonIndex = index; showConfig = true }
                            )
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(iconPool[btn.iconName] ?: MaterialSymbols.OutlinedFilled.Add,
                            contentDescription = btn.name, tint = accentColor, modifier = Modifier.size(28.dp))
                        Text(btn.name, fontSize = 11.sp, color = textColor, maxLines = 1)
                    }
                }
            }
        }

        if (showConfig && editingButtonIndex >= 0 && editingButtonIndex < buttons.size) {
            val btn = buttons[editingButtonIndex]
            var selectedTarget by remember { mutableStateOf(btn.targetActivity) }
            var selectedName by remember { mutableStateOf(btn.name) }
            var selectedIcon by remember { mutableStateOf(btn.iconName) }

            AlertDialog(
                onDismissRequest = { showConfig = false },
                title = { Text("配置：${btn.name}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(value = selectedName, onValueChange = { selectedName = it },
                            label = { Text("按钮名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Text("选择跳转目标", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        presetTargets.forEach { (name, target) ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedTarget = target; if (selectedName == btn.name) selectedName = name
                                }) {
                                RadioButton(selected = selectedTarget == target,
                                    onClick = { selectedTarget = target; if (selectedName == btn.name) selectedName = name })
                                Text(name, fontSize = 13.sp)
                            }
                        }
                        Text("图标", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            iconPool.entries.take(8).forEach { (name, icon) ->
                                IconButton(onClick = { selectedIcon = name }, modifier = Modifier.size(36.dp)) {
                                    Icon(icon, contentDescription = name,
                                        tint = if (selectedIcon == name) accentColor else subTextColor,
                                        modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val updated = buttons.toMutableList()
                        updated[editingButtonIndex] = QuickButtonConfig(id = btn.id, name = selectedName,
                            iconName = selectedIcon, targetActivity = selectedTarget)
                        saveQuickButtons(updated); showConfig = false; showToast("按钮配置已保存")
                    }) { Text("保存") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { saveQuickButtons(defaultQuickButtons); showConfig = false; showToast("已恢复默认") }) { Text("恢复默认") }
                        TextButton(onClick = { showConfig = false }) { Text("取消") }
                    }
                }
            )
        }
    }

    // ==================== 功能列表 ====================

    @Composable
    private fun SidePanelFeatureList(
        act: Activity, cardBgColor: Color, textColor: Color, subTextColor: Color, accentColor: Color, isDark: Boolean
    ) {
        val customFeatures = remember { loadCustomFeatures() }

        Card(colors = CardDefaults.cardColors(containerColor = cardBgColor), shape = RoundedCornerShape(12.dp)) {
            Column {
                if (momentsEntryEnabled) {
                    SidePanelFeatureItem(icon = MaterialSymbols.Outlined.Favorite, name = "朋友圈",
                        desc = "查看好友动态", textColor = textColor, subTextColor = subTextColor, accentColor = accentColor,
                        onClick = { startActivityByName(act, "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI") })
                }
                if (videoEntryEnabled) {
                    SidePanelFeatureItem(icon = MaterialSymbols.Outlined.Play_circle, name = "视频号",
                        desc = "发现精彩内容", textColor = textColor, subTextColor = subTextColor, accentColor = accentColor,
                        onClick = { startActivityByName(act, "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI") })
                }
                if (clearUnreadEnabled) {
                    SidePanelFeatureItem(icon = MaterialSymbols.OutlinedFilled.Check_circle, name = "清空未读",
                        desc = "一键清除所有未读消息", textColor = textColor, subTextColor = subTextColor, accentColor = accentColor,
                        onClick = {
                            try {
                                val apiClass = Class.forName("com.Johnny.wcx.features.api.core.WeConversationApi")
                                apiClass.getDeclaredMethod("markAllAsRead").invoke(null)
                                showToast("已清空全部未读消息")
                            } catch (e: Exception) { WeLogger.e(TAG, "清空未读失败", e); showToast("清空未读失败") }
                        })
                }
                customFeatures.forEach { feature ->
                    SidePanelFeatureItem(icon = iconPool[feature.iconName] ?: MaterialSymbols.OutlinedFilled.Add,
                        name = feature.name, desc = "自定义功能", textColor = textColor, subTextColor = subTextColor,
                        accentColor = accentColor,
                        onClick = {
                            if (feature.isCustomIntent) {
                                try {
                                    act.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        setClassName(act.packageName, feature.targetActivity)
                                    })
                                } catch (e: Exception) { showToast("无法打开: ${feature.name}") }
                            } else { startActivityByName(act, feature.targetActivity) }
                        })
                }
            }
        }
    }

    @Composable
    private fun SidePanelFeatureItem(
        icon: ImageVector, name: String, desc: String, textColor: Color, subTextColor: Color, accentColor: Color,
        onClick: () -> Unit
    ) {
        Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = textColor)
                Text(desc, fontSize = 12.sp, color = subTextColor, maxLines = 1)
            }
        }
    }

    // ==================== 每日一言 ====================

    @Composable
    private fun SidePanelDailyQuote(cardBgColor: Color, textColor: Color, subTextColor: Color, accentColor: Color) {
        var showConfig by remember { mutableStateOf(false) }
        val quoteText = remember { mutableStateOf("") }
        val isConfigured = dailyQuoteApiUrl.isNotBlank()
        LaunchedEffect(Unit) {
            if (isConfigured) quoteText.value = fetchDailyQuote()
        }

        val cardAlpha = if (isConfigured) 1f else 0.4f
        Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(
                onClick = {},
                onLongClick = { showConfig = true }
            ),
            colors = CardDefaults.cardColors(containerColor = cardBgColor.copy(alpha = cardAlpha)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("每日一言", fontSize = 12.sp, color = subTextColor, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))
                if (!isConfigured) {
                    Text("未配置API地址", fontSize = 14.sp, color = subTextColor)
                } else {
                    Text(quoteText.value.ifBlank { dailyQuoteFallback }, fontSize = 14.sp, color = textColor, lineHeight = 22.sp)
                }
            }
        }

        if (showConfig) {
            var localApi by remember { mutableStateOf(dailyQuoteApiUrl) }
            var localApiKey by remember { mutableStateOf(dailyQuoteApiKey) }
            var localInterval by remember { mutableStateOf(dailyQuoteRefreshInterval.toString()) }
            var localFallback by remember { mutableStateOf(dailyQuoteFallback) }

            AlertDialog(
                onDismissRequest = { showConfig = false },
                title = { Text("每日一言配置") },
                text = {
                    Column {
                        OutlinedTextField(value = localApi, onValueChange = { localApi = it },
                            label = { Text("API地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = localApiKey, onValueChange = { localApiKey = it },
                            label = { Text("API Key (可选)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = localInterval, onValueChange = { localInterval = it },
                            label = { Text("刷新间隔(秒)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = localFallback, onValueChange = { localFallback = it },
                            label = { Text("兜底文案") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        dailyQuoteApiUrl = localApi; dailyQuoteApiKey = localApiKey
                        dailyQuoteRefreshInterval = localInterval.toIntOrNull() ?: 3600; dailyQuoteFallback = localFallback
                        showConfig = false
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        dailyQuoteApiUrl = "https://api.03c3.cn/api/yl"; dailyQuoteApiKey = ""
                        dailyQuoteRefreshInterval = 3600; dailyQuoteFallback = "生活不止眼前的苟且，还有诗和远方"; showConfig = false
                    }) { Text("恢复默认") }
                }
            )
        }
    }

    // ==================== 设置弹窗 ====================

    @Composable
    private fun SidePanelSettingsDialog(onDismiss: () -> Unit) {
        var showCustomFeatureEditor by remember { mutableStateOf(false) }
        val customFeatures = remember { mutableStateListOf<CustomFeature>().apply { addAll(loadCustomFeatures()) } }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("侧边栏设置")
                    TextButton(onClick = {
                        headerEnabled = true; weatherEnabled = true; quickButtonsEnabled = true
                        momentsEntryEnabled = true; videoEntryEnabled = true; clearUnreadEnabled = true
                        wcxSettingsEnabled = true; dailyQuoteEnabled = true
                        saveCustomFeatures(emptyList()); customFeatures.clear(); onDismiss()
                        showToast("已恢复全部默认设置")
                    }) { Text("恢复默认") }
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingSwitchItem("顶部头像在线状态栏", headerEnabled) { headerEnabled = it }
                    SettingSwitchItem("时间语录区域", headerEnabled) { headerEnabled = it }
                    SettingSwitchItem("天气卡片", weatherEnabled) { weatherEnabled = it }
                    SettingSwitchItem("4个快捷按钮区", quickButtonsEnabled) { quickButtonsEnabled = it }
                    SettingSwitchItem("朋友圈条目", momentsEntryEnabled) { momentsEntryEnabled = it }
                    SettingSwitchItem("视频号条目", videoEntryEnabled) { videoEntryEnabled = it }
                    SettingSwitchItem("清空未读条目", clearUnreadEnabled) { clearUnreadEnabled = it }
                    SettingSwitchItem("WCX设置条目", wcxSettingsEnabled) { wcxSettingsEnabled = it }
                    SettingSwitchItem("每日一言模块", dailyQuoteEnabled) { dailyQuoteEnabled = it }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("自定义功能", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { showCustomFeatureEditor = true }) {
                            Icon(MaterialSymbols.OutlinedFilled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("新增")
                        }
                    }

                    customFeatures.forEachIndexed { index, feature ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(iconPool[feature.iconName] ?: MaterialSymbols.OutlinedFilled.Add,
                                contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(feature.name, modifier = Modifier.weight(1f))
                            IconButton(onClick = { customFeatures.removeAt(index); saveCustomFeatures(customFeatures.toList()) }) {
                                Icon(MaterialSymbols.OutlinedFilled.Delete, contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { saveCustomFeatures(customFeatures.toList()); onDismiss() }) { Text("完成") }
            }
        )

        if (showCustomFeatureEditor) {
            var newName by remember { mutableStateOf("") }
            var newTarget by remember { mutableStateOf("") }
            var newIcon by remember { mutableStateOf("Add") }
            var newIsCustomIntent by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { showCustomFeatureEditor = false },
                title = { Text("新增自定义功能") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(value = newName, onValueChange = { newName = it },
                            label = { Text("显示名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = newTarget, onValueChange = { newTarget = it },
                            label = { Text("跳转目标(Activity完整类名)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("自定义Intent", modifier = Modifier.weight(1f))
                            Switch(checked = newIsCustomIntent, onCheckedChange = { newIsCustomIntent = it })
                        }
                        Text("跳转预设", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        presetTargets.forEach { (name, target) ->
                            Text("$name · $target", color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().clickable { newTarget = target; if (newName.isBlank()) newName = name }
                                    .padding(vertical = 4.dp), fontSize = 12.sp)
                        }
                        Text("图标", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            iconPool.entries.take(8).forEach { (name, icon) ->
                                IconButton(onClick = { newIcon = name }, modifier = Modifier.size(36.dp)) {
                                    Icon(icon, contentDescription = name,
                                        tint = if (newIcon == name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newName.isNotBlank() && newTarget.isNotBlank()) {
                                customFeatures.add(CustomFeature(name = newName.trim(), targetActivity = newTarget.trim(),
                                    iconName = newIcon, isCustomIntent = newIsCustomIntent))
                                saveCustomFeatures(customFeatures.toList()); showCustomFeatureEditor = false
                            }
                        },
                        enabled = newName.isNotBlank() && newTarget.isNotBlank()
                    ) { Text("添加") }
                },
                dismissButton = { TextButton(onClick = { showCustomFeatureEditor = false }) { Text("取消") } }
            )
        }
    }

    @Composable
    private fun SettingSwitchItem(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp); Switch(checked = checked, onCheckedChange = onToggle)
        }
    }

    // ==================== 天气设置弹窗 ====================

    @Composable
    private fun WeatherSettingsDialog(act: Activity, onDismiss: () -> Unit) {
        var localCity by remember { mutableStateOf(weatherCity) }
        var localApi by remember { mutableStateOf(weatherApiUrl) }
        var localApiKey by remember { mutableStateOf(weatherApiKey) }
        var localInterval by remember { mutableStateOf(weatherRefreshInterval.toString()) }
        var localFallbackCity by remember { mutableStateOf(weatherFallbackCity) }
        var searchCity by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("天气设置") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("当前城市: $localCity", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { localCity = "北京"; showToast("已自动检测位置: $localCity") },
                            modifier = Modifier.weight(1f)) { Text("自动检测", fontSize = 12.sp) }
                        OutlinedButton(onClick = {
                            try {
                                val userInfo = act.reflekt().firstField { type = "com.tencent.mm.model.MultiUserInfo" }.get()
                                val region = userInfo?.reflekt()?.firstField { name = "region" }?.get() as? String
                                if (!region.isNullOrBlank()) { localCity = region; showToast("已从个人资料读取: $region") }
                                else showToast("个人资料中无地区信息")
                            } catch (e: Exception) { WeLogger.e(TAG, "读取个人资料失败", e); showToast("无法读取个人资料") }
                        }, modifier = Modifier.weight(1f)) { Text("从资料读取", fontSize = 12.sp) }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = searchCity, onValueChange = { searchCity = it },
                            label = { Text("搜索城市") }, modifier = Modifier.weight(1f), singleLine = true)
                        TextButton(onClick = { if (searchCity.isNotBlank()) localCity = searchCity.trim() }) { Text("确定") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = localApi, onValueChange = { localApi = it },
                        label = { Text("天气API地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = localApiKey, onValueChange = { localApiKey = it },
                        label = { Text("API Key (可选)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = localInterval, onValueChange = { localInterval = it },
                        label = { Text("刷新间隔(秒)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = localFallbackCity, onValueChange = { localFallbackCity = it },
                        label = { Text("兜底城市") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    weatherCity = localCity; weatherApiUrl = localApi; weatherApiKey = localApiKey
                    weatherRefreshInterval = localInterval.toIntOrNull() ?: 1800; weatherFallbackCity = localFallbackCity
                    cachedWeather = null; onDismiss(); showToast("天气设置已保存")
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    }

    // ==================== 数据获取 ====================

    private fun fetchWeatherData(): WeatherData? {
        val now = System.currentTimeMillis()
        if (cachedWeather != null && (now - lastWeatherFetchTime) < weatherRefreshInterval * 1000L) return cachedWeather
        // 配置不全不发起无效网络请求
        if (weatherApiUrl.isBlank()) return null
        return try {
            val city = weatherCity.ifBlank { weatherFallbackCity }
            val url = buildString {
                append(weatherApiUrl); append("?city=${URLEncoder.encode(city, "UTF-8")}")
            }
            val json = httpGet(url, weatherApiKey) ?: return null
            val obj = JSONObject(json); val data = obj.optJSONObject("data") ?: return null
            val weather = WeatherData(
                city = data.optString("city", city), temperature = data.optString("wendu", "--"),
                feelsLike = data.optString("ganmao", "--"), tempHigh = data.optString("high", "--"),
                tempLow = data.optString("low", "--"), humidity = data.optString("shidu", "--"),
                windSpeed = data.optString("fengli", "--"), weather = data.optString("type", ""),
                updateTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                weatherIcon = data.optString("type", "")
            )
            cachedWeather = weather; lastWeatherFetchTime = now; weather
        } catch (e: Exception) { WeLogger.e(TAG, "获取天气数据失败", e); cachedWeather }
    }

    private fun fetchQuoteText(): String {
        if (useSignature) {
            return try { WePrefs.getString("${PREFS_PREFIX}signature_cache")?.ifBlank { quoteFallback } ?: quoteFallback }
            catch (e: Exception) { quoteFallback }
        }
        if (quoteApiUrl.isBlank()) return quoteFallback
        return try {
            val json = httpGet(quoteApiUrl, quoteApiKey) ?: return quoteFallback
            JSONObject(json).optString("data", quoteFallback)
        } catch (e: Exception) { WeLogger.e(TAG, "获取语录失败", e); quoteFallback }
    }

    private fun fetchDailyQuote(): String {
        if (dailyQuoteApiUrl.isBlank()) return dailyQuoteFallback
        return try {
            val json = httpGet(dailyQuoteApiUrl, dailyQuoteApiKey) ?: return dailyQuoteFallback
            JSONObject(json).optString("data", dailyQuoteFallback)
        } catch (e: Exception) { WeLogger.e(TAG, "获取每日一言失败", e); dailyQuoteFallback }
    }

    private fun httpGet(urlStr: String, apiKey: String = ""): String? {
        var connection: HttpURLConnection? = null; var input: InputStream? = null
        return try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000; connection.readTimeout = 10000; connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (apiKey.isNotBlank()) { connection.setRequestProperty("X-API-Key", apiKey); connection.setRequestProperty("Authorization", "Bearer $apiKey") }
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            input = connection.inputStream; input.bufferedReader().readText()
        } catch (e: Exception) { null }
        finally { try { input?.close() } catch (_: Throwable) {}; try { connection?.disconnect() } catch (_: Throwable) {} }
    }

    // ==================== 头像加载 ====================

    private fun getAvatarCacheFile(act: Activity): File {
        val dir = File(act.cacheDir, AVATAR_CACHE_DIR); if (!dir.exists()) dir.mkdirs()
        return File(dir, "avatar_cache.png")
    }

    private fun loadWeChatAvatar(act: Activity): Bitmap? {
        val cacheFile = getAvatarCacheFile(act)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try { BitmapFactory.decodeFile(cacheFile.absolutePath)?.let { WeLogger.d(TAG, "头像: 从缓存加载成功"); return it } }
            catch (e: Throwable) { WeLogger.w(TAG, "头像: 缓存读取失败", e) }
        }
        var avatar: Bitmap? = null
        try {
            val selfWxId = WeApi.selfWxId
            if (selfWxId.isNotEmpty()) {
                val avatarUrl = WeDatabaseApi.getAvatarUrl(selfWxId)
                if (avatarUrl.isNotEmpty()) { avatar = downloadBitmap(avatarUrl); if (avatar != null) WeLogger.d(TAG, "头像: 策略A (CDN) 成功") }
            }
        } catch (e: Throwable) { WeLogger.w(TAG, "头像: 策略A 失败", e) }
        if (avatar == null) {
            try {
                val userInfo = act.reflekt().firstField { type = "com.tencent.mm.model.MultiUserInfo" }.get()
                if (userInfo != null) {
                    val avatarPath = listOf("avatar", "avatarFile", "headImgPath", "avatarPath")
                        .firstNotNullOfOrNull { fn -> try { userInfo.reflekt()?.firstField { name = fn }?.get() as? String } catch (_: Throwable) { null } }
                    if (!avatarPath.isNullOrBlank()) {
                        val f = File(avatarPath); if (f.exists()) { avatar = BitmapFactory.decodeFile(avatarPath); if (avatar != null) WeLogger.d(TAG, "头像: 策略B 成功") }
                    }
                }
            } catch (e: Throwable) { WeLogger.w(TAG, "头像: 策略B 失败", e) }
        }
        if (avatar == null) {
            try {
                val root = act.findViewById<View>(android.R.id.content)
                val avatarView = findAvatarInViewTree(root)
                if (avatarView != null) { avatar = viewToBitmap(avatarView); if (avatar != null) WeLogger.d(TAG, "头像: 策略C 成功") }
            } catch (e: Throwable) { WeLogger.w(TAG, "头像: 策略C 失败", e) }
        }
        if (avatar != null) {
            try { val fos = FileOutputStream(cacheFile); avatar.compress(Bitmap.CompressFormat.PNG, 90, fos); fos.close(); WeLogger.d(TAG, "头像: 已缓存") }
            catch (e: Throwable) { WeLogger.w(TAG, "头像: 缓存写入失败", e) }
        }
        return avatar
    }

    private fun refreshAvatar(act: Activity): Bitmap? {
        val cacheFile = getAvatarCacheFile(act); if (cacheFile.exists()) cacheFile.delete()
        return loadWeChatAvatar(act)
    }

    private fun loadWeChatNickname(act: Activity): String {
        try { val name = WeDatabaseApi.getSelfProfileField(SelfProfileField.NAME); if (name is String && name.isNotBlank()) return name }
        catch (e: Throwable) { WeLogger.w(TAG, "昵称: 策略A 失败", e) }
        try {
            val userInfo = act.reflekt().firstField { type = "com.tencent.mm.model.MultiUserInfo" }.get()
            if (userInfo != null) {
                for (fn in listOf("nickname", "nickName", "userNickname", "displayName")) {
                    try { val nick = userInfo.reflekt()?.firstField { name = fn }?.get() as? String; if (!nick.isNullOrBlank()) return nick }
                    catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) { WeLogger.w(TAG, "昵称: 策略B 失败", e) }
        return ""
    }

    private fun findAvatarInViewTree(root: View): ImageView? {
        val queue = ArrayDeque<View>(); queue.add(root)
        val candidates = mutableListOf<ImageView>()
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v is ImageView && v.isVisible && v.drawable != null) {
                if (v.width in 48..200 && v.height in 48..200) candidates.add(v)
            }
            if (v is ViewGroup) { for (i in 0 until v.childCount) { v.getChildAt(i)?.let { queue.add(it) } } }
        }
        return candidates.maxByOrNull { it.visibleArea() }
    }

    private fun View.visibleArea(): Int { if (!isVisible) return 0; val r = Rect(); return if (getGlobalVisibleRect(r)) r.width() * r.height() else 0 }
    private val View.isVisible: Boolean get() = visibility == View.VISIBLE && width > 0 && height > 0

    private fun viewToBitmap(view: View): Bitmap? {
        return try { val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); val canvas = Canvas(bmp); view.draw(canvas); bmp }
        catch (e: Throwable) { null }
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        var connection: HttpURLConnection? = null; var input: InputStream? = null
        return try {
            val url = URL(urlStr); connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000; connection.readTimeout = 15000; connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0"); connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            input = connection.inputStream; BitmapFactory.decodeStream(input)
        } catch (e: Throwable) { null }
        finally { try { input?.close() } catch (_: Throwable) {}; try { connection?.disconnect() } catch (_: Throwable) {} }
    }

    private fun startActivityByName(context: Context, className: String) {
        if (className.isBlank()) return
        try {
            val intent = Intent().apply { setClassName(context.packageName, className); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        } catch (e: Exception) { WeLogger.e(TAG, "启动Activity失败: $className", e); showToast("无法打开该功能") }
    }

    // ==================== 设置入口 ====================

    override fun onClick(context: ComponentActivity) {
        if (!BuildConfig.BEAUTIFY_ENABLED) {
            showToast("侧边栏功能编译开关已关闭")
            return
        }
        showComposeDialog(context) {
            var localMaster by remember { mutableStateOf(masterEnabled) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("微信主页侧滑侧边栏") },
                text = {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("启用侧边栏"); Switch(checked = localMaster, onCheckedChange = { localMaster = it })
                        }
                        Text("开启后在微信主页左上角显示唤起按钮，点击可打开侧边栏面板",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        masterEnabled = localMaster
                        if (localMaster) enable() else disable()
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
            )
        }
    }

    private const val POLL_INTERVAL_MS = 2000L
}