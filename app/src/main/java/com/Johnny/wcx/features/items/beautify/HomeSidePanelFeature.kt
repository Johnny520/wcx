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
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 微信主页侧滑侧边栏功能（XML+原生 View 实现）
 *
 * Bug Fix (v203): 全部重写为 XML 布局 + 原生 View，移除全部 Compose 代码 + WindowManager 全局悬浮窗。
 *
 * 设计原则：
 * - 触发按钮/面板根：加到微信 Activity 的 decorView，依赖 Activity 生命周期
 * - 不使用全局 WindowManager / TYPE_APPLICATION_OVERLAY 浮动窗
 * - ActivityLifecycleCallbacks 监听 onResume/onPause/onDestroy，自动 addView/removeView
 * - addView/removeView 做判空保护，全部 UI 加 try-catch，异常仅记日志
 * - 关闭开关后，注销 ActivityLifecycleCallbacks + 清空所有 decorView 视图
 *
 * 备注：本项目没有 res/layout/ 与 themes.xml，因此面板用 Kotlin 代码动态构造 View，
 *       面板纯 Kotlin 代码动态构造 View（项目无 res/layout/ 与 themes.xml）
 */
@Feature(
    name = "微信主页侧滑侧边栏",
    categories = ["界面美化"],
    description = "在微信主页左侧添加侧滑面板，包含天气、快捷按钮、每日一言等模块"
)
object HomeSidePanelFeature : ClickableFeature() {

    override val alwaysEnabled = false
    override val noSwitchWidget = false

    // ==================== 视图引用（操作 decorView 上的 view） ====================

    @Volatile
    private var triggerView: View? = null
    @Volatile
    private var panelRootView: View? = null
    @Volatile
    private var attachedActivity: Activity? = null

    // ==================== ActivityLifecycleCallbacks ====================

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}

        override fun onActivityResumed(activity: Activity) {
            if (!masterEnabled) return
            try {
                if (isLauncherUI(activity)) {
                    if (attachedActivity !== activity) {
                        removeAllViews()
                        attachedActivity = activity
                    }
                    if (triggerView == null && panelRootView == null) {
                        attachTriggerButton(activity)
                    }
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "onActivityResumed 异常", e)
            }
        }

        override fun onActivityPaused(activity: Activity) {
            try {
                if (attachedActivity === activity) removeAllViews()
            } catch (e: Throwable) {
                WeLogger.e(TAG, "onActivityPaused 异常", e)
            }
        }

        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            try {
                if (attachedActivity === activity) {
                    removeAllViews()
                    attachedActivity = null
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "onActivityDestroyed 异常", e)
            }
        }
    }

    @Volatile
    private var callbacksRegistered = false

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
        return try {
            Json.decodeFromString<List<QuickButtonConfig>>(json)
        } catch (e: Exception) {
            WeLogger.e(TAG, "解析快捷按钮配置失败", e)
            defaultQuickButtons
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
        return try {
            Json.decodeFromString<List<CustomFeature>>(json)
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

    private val presetTargets: List<Pair<String, String>> = listOf(
        "扫一扫" to "com.tencent.mm.plugin.scanner.ui.BaseScanUI",
        "朋友圈" to "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI",
        "收藏" to "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI",
        "钱包" to "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI",
        "视频号" to "com.tencent.mm.plugin.finder.ui.FinderHomeUI",
        "通讯录" to "com.tencent.mm.ui.contact.ContactsUI",
        "我" to "com.tencent.mm.ui.MeTabUI",
        "设置" to "com.tencent.mm.ui.setting.SettingsUI"
    )

    // ==================== 数据缓存 ====================

    private var cachedWeather: WeatherData? = null
    private var lastWeatherFetchTime: Long = 0L
    private var cachedDailyQuote: String = ""
    private var lastDailyQuoteFetchTime: Long = 0L

    data class WeatherData(
        val city: String,
        val temperature: String,
        val feelsLike: String,
        val tempHigh: String,
        val tempLow: String,
        val humidity: String,
        val windSpeed: String,
        val weather: String,
        val updateTime: String,
        val weatherIcon: String
    )

    // ==================== 生命周期入口 ====================

    override fun onEnable() {
        if (!BuildConfig.BEAUTIFY_ENABLED) {
            WeLogger.w(TAG, "侧边栏功能编译开关已关闭，跳过启用")
            return
        }
        masterEnabled = true
        WeLogger.i(TAG, "侧边栏功能已启用")
        registerActivityCallbacks()
    }

    override fun onDisable() {
        masterEnabled = false
        WeLogger.i(TAG, "侧边栏功能已关闭")
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
        } catch (e: Throwable) {
            WeLogger.e(TAG, "注册 ActivityLifecycleCallbacks 失败", e)
        }
    }

    private fun unregisterActivityCallbacks() {
        if (!callbacksRegistered) return
        try {
            currentApplication()?.unregisterActivityLifecycleCallbacks(activityCallbacks)
            callbacksRegistered = false
        } catch (e: Throwable) {
            WeLogger.e(TAG, "注销 ActivityLifecycleCallbacks 失败", e)
        }
    }

    private fun currentApplication(): Application? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            atClass.getDeclaredMethod("currentApplication").invoke(null) as? Application
        } catch (e: Throwable) { null }
    }

    // ==================== Activity 检测 ====================

    private fun isLauncherUI(act: Activity): Boolean {
        return try { !act.isFinishing && act.javaClass.name == "com.tencent.mm.ui.LauncherUI" }
        catch (_: Throwable) { false }
    }

    private fun findLauncherUI(): Activity? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val activitiesField = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activities = activitiesField.get(at) as? Map<*, *> ?: return null
            for (record in activities.values) {
                val activity = record?.javaClass?.getDeclaredField("activity")
                    ?.apply { isAccessible = true }
                    ?.get(record) as? Activity ?: continue
                if (isLauncherUI(activity)) return activity
            }
            null
        } catch (e: Throwable) { null }
    }

    private fun detectWeChatOfficialEntry(act: Activity): Int {
        return try {
            val root = act.window?.decorView ?: return 0
            val queue = ArrayDeque<View>()
            queue.add(root)
            val offsetCandidates = mutableListOf<Int>()
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) v.getChildAt(i)?.let { queue.add(it) }
                }
                val tag = v.tag
                if (tag != null && tag.toString().contains("ai", ignoreCase = true)) {
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    if (loc[0] in 1..120 && loc[1] in 40..200) {
                        offsetCandidates.add(v.width + 16)
                    }
                }
            }
            offsetCandidates.maxOrNull() ?: 0
        } catch (e: Throwable) { 0 }
    }

    // ==================== 视图管理（依赖 decorView） ====================

    private fun attachTriggerButton(act: Activity) {
        if (triggerView != null) return
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
            // 中心图标（用三道横杠模拟"≡"）
            val iconHost = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    (22 * d).toInt(), (16 * d).toInt(), Gravity.CENTER
                )
                gravity = Gravity.CENTER
            }
            repeat(3) {
                val bar = View(act).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (2 * d).toInt()).apply {
                        topMargin = (1 * d).toInt()
                    }
                    setBackgroundColor(AndroidColor.parseColor(if (isDarkMode(act)) "#64B5F6" else "#1976D2"))
                }
                iconHost.addView(bar)
            }
            trigger.addView(iconHost)
            val lp = FrameLayout.LayoutParams(triggerSizePx, triggerSizePx).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = (16 * d).toInt() + extraOffset
                topMargin = statusBarH + (8 * d).toInt()
            }
            if (trigger.parent != null) (trigger.parent as? ViewGroup)?.removeView(trigger)
            decorView.addView(trigger, lp)
            triggerView = trigger
        } catch (e: Throwable) {
            WeLogger.e(TAG, "attachTriggerButton 异常", e)
            try { triggerView?.let { if (it.parent != null) (it.parent as? ViewGroup)?.removeView(it) } } catch (_: Throwable) {}
            triggerView = null
        }
    }

    /**
     * 主面板：用代码构造。
     * 结构：FrameLayout(root) > [View(scrim), LinearLayout(container) > [header_bar, divider, ScrollView(content)]]
     */
    private fun showPanel(act: Activity) {
        if (panelRootView != null) return
        val decorView = act.window?.decorView as? ViewGroup ?: return
        try {
            val dark = isDarkMode(act)
            val panel = FrameLayout(act).apply { tag = "home_side_panel_overlay" }

            // scrim
            val scrim = View(act).apply {
                setBackgroundColor(AndroidColor.parseColor("#66000000"))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setOnClickListener { try { hidePanel() } catch (e: Throwable) { WeLogger.e(TAG, "scrim 点击异常", e) } }
            }
            panel.addView(scrim)

            // container 左侧 280dp
            val d = act.resources.displayMetrics.density
            val container = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    dp(280, act),
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.START or Gravity.TOP }
                setBackgroundColor(AndroidColor.parseColor(if (dark) "#1E1E1E" else "#F5F5F5"))
            }
            panel.addView(container)

            // header bar
            val sbH = getStatusBarHeight(act)
            val headerBar = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(16, act), sbH, dp(8, act), dp(8, act)) }
            }
            val title = TextView(act).apply {
                text = "侧边栏"
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(AndroidColor.parseColor(if (dark) "#E0E0E0" else "#212121"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnSettings = makeIconButton(act, "⚙", dp(40, act)) {
                try { showPanelSettingsDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "设置按钮异常", e) }
            }
            val btnClose = makeIconButton(act, "✕", dp(40, act)) {
                try { hidePanel() } catch (e: Throwable) { WeLogger.e(TAG, "关闭按钮异常", e) }
            }
            btnSettings.setTextColor(AndroidColor.parseColor(if (dark) "#64B5F6" else "#1976D2"))
            btnClose.setTextColor(AndroidColor.parseColor(if (dark) "#AAAAAA" else "#999999"))
            headerBar.addView(title); headerBar.addView(btnSettings); headerBar.addView(btnClose)
            container.addView(headerBar)

            // divider
            val divider = View(act).apply {
                setBackgroundColor(AndroidColor.parseColor(if (dark) "#3A3A3A" else "#E0E0E0"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            }
            container.addView(divider)

            // ScrollView(content)
            val scroll = ScrollView(act).apply {
                isFillViewport = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0, 1f
                )
            }
            val content = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(12, act), dp(8, act), dp(12, act), dp(24, act)) }
            }
            scroll.addView(content)
            container.addView(scroll)

            // 撑满 decorView
            val panelLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            if (panel.parent != null) (panel.parent as? ViewGroup)?.removeView(panel)
            decorView.addView(panel, panelLp)
            panelRootView = panel

            // 缓存 content 引用到 view tag 供后续刷新
            content.tag = "wcx_panel_content"

            // 隐藏触发按钮（不删除，hidePanel 时恢复）
            hideTrigger()

            // 异步填充内容
            fillPanelContent(act, content)

            WeLogger.d(TAG, "侧边栏面板已展开")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "showPanel 异常", e)
            try { removePanelInternal() } catch (_: Throwable) {}
        }
    }

    private fun makeIconButton(act: Activity, char: String, sizePx: Int, onClick: () -> Unit): TextView {
        return TextView(act).apply {
            text = char
            textSize = 20f
            gravity = Gravity.CENTER
            width = sizePx
            height = sizePx
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            background = makeRippleBg(AndroidColor.parseColor("#22000000"))
        }
    }

    private fun makeRippleBg(color: Int): android.graphics.drawable.Drawable {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            RippleDrawable(android.content.res.ColorStateList.valueOf(color), null, null)
        } else {
            GradientDrawable().apply { setColor(color) }
        }
    }

    private fun hideTrigger() {
        val tv = triggerView ?: return
        try { if (tv.parent != null) (tv.parent as? ViewGroup)?.removeView(tv) }
        catch (e: Throwable) { WeLogger.w(TAG, "移除触发按钮失败", e) }
        triggerView = null
    }

    private fun removePanelInternal() {
        val panel = panelRootView ?: return
        try { if (panel.parent != null) (panel.parent as? ViewGroup)?.removeView(panel) }
        catch (e: Throwable) { WeLogger.w(TAG, "移除面板失败", e) }
        panelRootView = null
    }

    private fun hidePanel() {
        removePanelInternal()
        val act = attachedActivity ?: findLauncherUI()
        if (act != null && triggerView == null) attachTriggerButton(act)
    }

    private fun removeAllViews() {
        try {
            removePanelInternal()
            val tv = triggerView; triggerView = null
            if (tv != null && tv.parent != null) {
                (tv.parent as? ViewGroup)?.removeView(tv)
            }
        } catch (e: Throwable) { WeLogger.e(TAG, "removeAllViews 异常", e) }
    }

    // ==================== 面板内容填充（纯代码构造）====================

    private fun fillPanelContent(act: Activity, content: LinearLayout) {
        try {
            content.removeAllViews()

            if (headerEnabled) {
                try {
                    val card = makeHeaderCard(act)
                    content.addView(card)
                } catch (e: Throwable) { WeLogger.e(TAG, "填充头部卡片异常", e) }
            }
            if (weatherEnabled) {
                try { content.addView(makeWeatherCard(act)) } catch (e: Throwable) { WeLogger.e(TAG, "天气卡片异常", e) }
            }
            if (quickButtonsEnabled) {
                try { content.addView(makeQuickButtonsRow(act)) } catch (e: Throwable) { WeLogger.e(TAG, "快捷按钮异常", e) }
            }
            // 功能列表（即使开关全关也展示自定义）
            try { fillFeatureList(act, content) } catch (e: Throwable) { WeLogger.e(TAG, "功能列表异常", e) }
            if (dailyQuoteEnabled) {
                try { content.addView(makeQuoteCard(act)) } catch (e: Throwable) { WeLogger.e(TAG, "语录异常", e) }
            }
        } catch (e: Throwable) { WeLogger.e(TAG, "fillPanelContent 异常", e) }
    }

    private fun makeHeaderCard(act: Activity): View {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg(act)
            setPadding(dp(16, act), dp(16, act), dp(16, act), dp(16, act))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { val m = dp(8, act); setMargins(0, m, 0, m) }
        }

        // 主行：头像 + 昵称区 + 刷新按钮
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatar = ImageView(act).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56, act), dp(56, act))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AndroidColor.parseColor(if (isDarkMode(act)) "#555555" else "#E0E0E0"))
            }
            contentDescription = "头像"
            tag = "wcx_header_avatar"
        }
        val infoCol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12, act)
            }
        }
        val statusRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setOnLongClickListener {
                try { showStatusConfigDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "状态配置异常", e) }
                true
            }
        }
        val dot = View(act).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8, act), dp(8, act))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AndroidColor.parseColor(if (isOnline) "#4CAF50" else "#9E9E9E"))
            }
        }
        val statusText = TextView(act).apply {
            text = onlineStatus
            textSize = 13f
            setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#AAAAAA" else "#999999"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6, act)
            }
        }
        statusRow.addView(dot); statusRow.addView(statusText)
        infoCol.addView(statusRow)

        val nickname = TextView(act).apply {
            text = ""
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#E0E0E0" else "#212121"))
            setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4, act)
            }
            tag = "wcx_header_nickname"
        }
        infoCol.addView(nickname)

        val timeText = TextView(act).apply {
            text = try { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) } catch (_: Throwable) { "00:00" }
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#E0E0E0" else "#212121"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            tag = "wcx_header_time"
        }
        infoCol.addView(timeText)

        val dateText = TextView(act).apply {
            text = try { SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE).format(Date()) } catch (_: Throwable) { "" }
            textSize = 12f
            setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#888888" else "#999999"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2, act)
            }
            tag = "wcx_header_date"
        }
        infoCol.addView(dateText)

        val refreshBtn = TextView(act).apply {
            text = "↻"
            textSize = 18f
            gravity = Gravity.CENTER
            width = dp(32, act); height = dp(32, act)
            setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#888888" else "#999999"))
            isClickable = true; isFocusable = true
            background = makeRippleBg(AndroidColor.parseColor("#22000000"))
            setOnClickListener {
                try { refreshHeader(act, nickname, timeText, dateText, avatar) } catch (e: Throwable) { WeLogger.e(TAG, "刷新异常", e) }
            }
        }

        row.addView(avatar); row.addView(infoCol); row.addView(refreshBtn)
        card.addView(row)

        // 语录（长按配置）
        val quote = TextView(act).apply {
            val qText = try { fetchQuoteText() } catch (_: Throwable) { quoteFallback }
            text = if (quoteApiUrl.isBlank() && !useSignature) "未配置API地址" else qText
            textSize = 13f
            setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#888888" else "#999999"))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8, act)
            }
            isLongClickable = true
            setOnLongClickListener {
                try { showQuoteConfigDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "语录配置异常", e) }
                true
            }
        }
        card.addView(quote)

        // 异步加载头像/昵称
        mainHandler.post {
            try {
                val bmp = loadWeChatAvatar(act)
                if (bmp != null) avatar.setImageBitmap(bmp)
                nickname.text = loadWeChatNickname(act)
            } catch (e: Throwable) { WeLogger.e(TAG, "异步加载头像失败", e) }
        }

        return card
    }

    private fun refreshHeader(act: Activity, nickname: TextView, timeText: TextView, dateText: TextView, avatar: ImageView) {
        try {
            mainHandler.post {
                try {
                    val bmp = loadWeChatAvatar(act)
                    if (bmp != null) avatar.setImageBitmap(bmp)
                    nickname.text = loadWeChatNickname(act)
                } catch (e: Throwable) { WeLogger.e(TAG, "刷新头像失败", e) }
            }
            timeText.text = try { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) } catch (_: Throwable) { timeText.text }
            dateText.text = try { SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE).format(Date()) } catch (_: Throwable) { dateText.text }
            showToast("已刷新")
        } catch (e: Throwable) { WeLogger.e(TAG, "refreshHeader 异常", e) }
    }

    private fun makeWeatherCard(act: Activity): View {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg(act)
            setPadding(dp(16, act), dp(16, act), dp(16, act), dp(16, act))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { val m = dp(8, act); setMargins(0, m, 0, m) }
            isLongClickable = true
            setOnLongClickListener {
                try { showWeatherConfigDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "天气配置异常", e) }
                true
            }
        }
        val configured = weatherApiUrl.isNotBlank()
        card.alpha = if (configured) 1f else 0.4f

        if (!configured) {
            val txt = TextView(act).apply {
                text = "未配置 API 地址"
                textSize = 14f
                setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#AAAAAA" else "#999999"))
                gravity = Gravity.CENTER
            }
            card.addView(txt)
            return card
        }

        val row1 = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cityCol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val city = TextView(act).apply { text = "--"; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#E0E0E0" else "#212121")) }
        val update = TextView(act).apply { text = ""; textSize = 11f; setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#888888" else "#999999")) }
        cityCol.addView(city); cityCol.addView(update)

        val icon = TextView(act).apply { text = "☁"; textSize = 28f; setTextColor(AndroidColor.parseColor("#1976D2")) }
        row1.addView(cityCol); row1.addView(icon)
        card.addView(row1)

        val row2 = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8, act) }
        }
        val temp = TextView(act).apply { text = "--"; textSize = 36f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#E0E0E0" else "#212121")); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val sideCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val feels = TextView(act).apply { text = "体感 --"; textSize = 12f; setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#888888" else "#999999")) }
        val hl = TextView(act).apply { text = "-- / --"; textSize = 12f; setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#888888" else "#999999")) }
        val hw = TextView(act).apply { text = "湿度 -- 风速 --"; textSize = 11f; setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#888888" else "#999999")) }
        sideCol.addView(feels); sideCol.addView(hl); sideCol.addView(hw)

        row2.addView(temp); row2.addView(sideCol)
        card.addView(row2)

        val typeText = TextView(act).apply {
            textSize = 13f
            setTextColor(AndroidColor.parseColor("#1976D2"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4, act) }
        }
        card.addView(typeText)

        // 异步填充
        mainHandler.post {
            try {
                val w = fetchWeatherData()
                if (w != null) {
                    city.text = w.city
                    update.text = w.updateTime
                    temp.text = w.temperature
                    feels.text = "体感 ${w.feelsLike}"
                    hl.text = "${w.tempHigh} / ${w.tempLow}"
                    hw.text = "湿度 ${w.humidity} 风速 ${w.windSpeed}"
                    typeText.text = w.weather
                }
            } catch (e: Throwable) { WeLogger.e(TAG, "异步天气异常", e) }
        }
        return card
    }

    private fun makeQuickButtonsRow(act: Activity): View {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            background = makeCardBg(act)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { val m = dp(8, act); setMargins(0, m, 0, m) }
            setPadding(dp(8, act), dp(8, act), dp(8, act), dp(8, act))
        }
        val buttons = loadQuickButtons().take(4)
        buttons.forEach { btn ->
            val item = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4, act), 0, dp(4, act), 0) }
                isClickable = true; isFocusable = true
                background = makeRippleBg(AndroidColor.parseColor("#11000000"))
                setOnClickListener {
                    try { startActivityByName(act, btn.targetActivity) }
                    catch (e: Throwable) { WeLogger.e(TAG, "快捷按钮点击异常", e) }
                }
                setOnLongClickListener {
                    try { showQuickButtonEditor(act, btn) } catch (e: Throwable) { WeLogger.e(TAG, "按钮配置异常", e) }
                    true
                }
            }
            val iconBg = FrameLayout(act).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40, act), dp(40, act))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(AndroidColor.parseColor("#E3F2FD"))
                }
            }
            val icon = TextView(act).apply {
                text = iconPool[btn.iconName] ?: "+"
                textSize = 22f
                setTextColor(AndroidColor.parseColor("#1976D2"))
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            iconBg.addView(icon)
            val label = TextView(act).apply {
                text = btn.name
                textSize = 11f
                setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#E0E0E0" else "#212121"))
                gravity = Gravity.CENTER
                setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4, act) }
            }
            item.addView(iconBg); item.addView(label)
            card.addView(item)
        }
        return card
    }

    private fun fillFeatureList(act: Activity, container: LinearLayout) {
        data class Entry(val icon: String, val label: String, val onClick: () -> Unit)
        val entries = mutableListOf<Entry>()

        if (momentsEntryEnabled) entries.add(Entry("♥", "朋友圈") { startActivityByName(act, "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI") })
        if (videoEntryEnabled) entries.add(Entry("▶", "视频号") { startActivityByName(act, "com.tencent.mm.plugin.finder.ui.FinderHomeUI") })
        if (clearUnreadEnabled) entries.add(Entry("✓", "清空未读") {
            try {
                Toast.makeText(act, "已尝试清空未读（占位）", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) { WeLogger.e(TAG, "清空未读异常", e) }
        })
        if (wcxSettingsEnabled) entries.add(Entry("⚙", "WCX 设置") {
            try {
                val intent = Intent(act, Class.forName("com.Johnny.wcx.activity.settings.SettingsActivity"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                act.startActivity(intent)
            } catch (e: Throwable) {
                WeLogger.e(TAG, "WCX 设置启动异常", e)
                showToast("无法打开WCX设置")
            }
        })

        val customs = loadCustomFeatures()
        customs.forEach { cf ->
            entries.add(Entry(iconPool[cf.iconName] ?: "+", cf.name) {
                if (cf.isCustomIntent) {
                    try {
                        val intent = Intent().apply {
                            setClassName(act.packageName, cf.targetActivity)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        act.startActivity(intent)
                    } catch (e: Throwable) { WeLogger.e(TAG, "自定义Intent异常", e); showToast("无法打开该功能") }
                } else startActivityByName(act, cf.targetActivity)
            })
        }

        entries.forEach { e ->
            val item = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = makeCardBg(act)
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { val m = dp(2, act); setMargins(0, m, 0, m) }
                setPadding(dp(16, act), dp(12, act), dp(16, act), dp(12, act))
                background = makeRippleBg(AndroidColor.parseColor("#11000000"))
                setOnClickListener { e.onClick() }
            }
            val icon = TextView(act).apply {
                text = e.icon; textSize = 20f
                setTextColor(AndroidColor.parseColor("#1976D2"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(28, act), dp(28, act))
            }
            val label = TextView(act).apply {
                text = e.label; textSize = 15f
                setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#E0E0E0" else "#212121"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12, act) }
            }
            val arrow = TextView(act).apply { text = "›"; textSize = 20f; setTextColor(AndroidColor.parseColor("#CCCCCC")) }
            item.addView(icon); item.addView(label); item.addView(arrow)
            container.addView(item)
        }
    }

    private fun makeQuoteCard(act: Activity): View {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg(act)
            setPadding(dp(16, act), dp(16, act), dp(16, act), dp(16, act))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { val m = dp(8, act); setMargins(0, m, 0, m) }
        }
        val titleRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val q = TextView(act).apply { text = "❝"; textSize = 20f; setTextColor(AndroidColor.parseColor("#1976D2")) }
        val t = TextView(act).apply { text = "每日一言"; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#E0E0E0" else "#212121")) ; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6, act) } }
        titleRow.addView(q); titleRow.addView(t); card.addView(titleRow)

        val text = TextView(act).apply {
            text = if (dailyQuoteApiUrl.isBlank()) "未配置API地址" else try { fetchDailyQuote() } catch (_: Throwable) { dailyQuoteFallback }
            textSize = 13f
            setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#BBBBBB" else "#666666"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8, act) }
        }
        card.addView(text)
        return card
    }

    // ==================== 公共工具 ====================

    private fun makeCardBg(act: Activity): android.graphics.drawable.Drawable {
        val dark = isDarkMode(act)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12, act).toFloat()
            setColor(AndroidColor.parseColor(if (dark) "#2C2C2C" else "#FFFFFF"))
        }
    }

    // ==================== 设置/配置弹窗 ====================

    private fun showPanelSettingsDialog(act: Activity) {
        try {
            val stateHolder = booleanArrayOf(masterEnabled)
            val container = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20, act), dp(12, act), dp(20, act), dp(12, act)) }
            addSwitchRow(container, act, "顶部头像在线状态栏", headerEnabled) { headerEnabled = it }
            addSwitchRow(container, act, "天气卡片", weatherEnabled) { weatherEnabled = it }
            addSwitchRow(container, act, "4个快捷按钮区", quickButtonsEnabled) { quickButtonsEnabled = it }
            addSwitchRow(container, act, "朋友圈条目", momentsEntryEnabled) { momentsEntryEnabled = it }
            addSwitchRow(container, act, "视频号条目", videoEntryEnabled) { videoEntryEnabled = it }
            addSwitchRow(container, act, "清空未读条目", clearUnreadEnabled) { clearUnreadEnabled = it }
            addSwitchRow(container, act, "WCX 设置条目", wcxSettingsEnabled) { wcxSettingsEnabled = it }
            addSwitchRow(container, act, "每日一言模块", dailyQuoteEnabled) { dailyQuoteEnabled = it }

            val scroll = ScrollView(act).apply { addView(container) }
            AlertDialog.Builder(act)
                .setTitle("侧边栏设置")
                .setView(scroll)
                .setPositiveButton("完成") { d, _ -> d.dismiss() }
                .setNegativeButton("恢复默认") { d, _ ->
                    try {
                        headerEnabled = true; weatherEnabled = true; quickButtonsEnabled = true
                        momentsEntryEnabled = true; videoEntryEnabled = true; clearUnreadEnabled = true
                        wcxSettingsEnabled = true; dailyQuoteEnabled = true
                        saveCustomFeatures(emptyList())
                        showToast("已恢复默认")
                        refreshPanelContent(act)
                    } catch (e: Throwable) { WeLogger.e(TAG, "恢复默认异常", e) }
                    d.dismiss()
                }
                .show()
        } catch (e: Throwable) { WeLogger.e(TAG, "showPanelSettingsDialog 异常", e) }
    }

    private fun refreshPanelContent(act: Activity) {
        try {
            val panel = panelRootView ?: return
            val scroll = findScrollView(panel)
            val content = scroll?.getChildAt(0) as? LinearLayout ?: return
            fillPanelContent(act, content)
        } catch (e: Throwable) { WeLogger.e(TAG, "refreshPanelContent 异常", e) }
    }

    private fun findScrollView(root: View): ScrollView? {
        if (root is ScrollView) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            findScrollView(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun addSwitchRow(container: LinearLayout, act: Activity, label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        try {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { val m = dp(4, act); setMargins(0, m, 0, m) }
            }
            val tv = TextView(act).apply {
                text = label; textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sw = Switch(act).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, c -> try { onChange(c) } catch (e: Throwable) { WeLogger.e(TAG, "switch 异常", e) } }
            }
            row.addView(tv); row.addView(sw); container.addView(row)
        } catch (e: Throwable) { WeLogger.e(TAG, "addSwitchRow 异常", e) }
    }

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
        } catch (e: Throwable) { WeLogger.e(TAG, "showStatusConfigDialog 异常", e) }
    }

    private fun showQuoteConfigDialog(act: Activity) {
        try {
            val signHolder = booleanArrayOf(useSignature)
            val urlHolder = arrayOf(quoteApiUrl); val keyHolder = arrayOf(quoteApiKey); val fbHolder = arrayOf(quoteFallback)
            val container = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20, act), dp(12, act), dp(20, act), dp(12, act)) }
            val swRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val swLabel = TextView(act).apply { text = "使用签名档"; textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            val sw = Switch(act).apply { isChecked = signHolder[0]; setOnCheckedChangeListener { _, c -> signHolder[0] = c } }
            swRow.addView(swLabel); swRow.addView(sw); container.addView(swRow)
            container.addView(EditText(act).apply { setText(urlHolder[0]); hint = "语录 API 地址" })
            container.addView(EditText(act).apply { setText(keyHolder[0]); hint = "API Key（可选）" })
            container.addView(EditText(act).apply { setText(fbHolder[0]); hint = "兜底语录" })

            AlertDialog.Builder(act)
                .setTitle("时间语录配置")
                .setView(ScrollView(act).apply { addView(container) })
                .setPositiveButton("保存") { d, _ ->
                    try { useSignature = signHolder[0]; refreshPanelContent(act) } catch (e: Throwable) { WeLogger.e(TAG, "保存语录异常", e) }
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Throwable) { WeLogger.e(TAG, "showQuoteConfigDialog 异常", e) }
    }

    private fun showWeatherConfigDialog(act: Activity) {
        try {
            val cityHolder = arrayOf(weatherCity); val urlHolder = arrayOf(weatherApiUrl); val keyHolder = arrayOf(weatherApiKey)
            val container = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20, act), dp(12, act), dp(20, act), dp(12, act)) }
            container.addView(EditText(act).apply { setText(cityHolder[0]); hint = "城市" })
            container.addView(EditText(act).apply { setText(urlHolder[0]); hint = "天气 API 地址" })
            container.addView(EditText(act).apply { setText(keyHolder[0]); hint = "API Key（可选）" })
            AlertDialog.Builder(act)
                .setTitle("天气配置")
                .setView(ScrollView(act).apply { addView(container) })
                .setPositiveButton("保存") { d, _ ->
                    try { refreshPanelContent(act) } catch (e: Throwable) { WeLogger.e(TAG, "保存天气异常", e) }
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Throwable) { WeLogger.e(TAG, "showWeatherConfigDialog 异常", e) }
    }

    private fun showQuickButtonEditor(act: Activity, btn: QuickButtonConfig) {
        try {
            val nameInput = EditText(act).apply { setText(btn.name); hint = "按钮名称" }
            val targetInput = EditText(act).apply { setText(btn.targetActivity); hint = "Activity 类名" }
            val container = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20, act), dp(12, act), dp(20, act), dp(12, act))
                addView(nameInput); addView(targetInput)
            }
            AlertDialog.Builder(act)
                .setTitle("配置快捷按钮")
                .setView(container)
                .setPositiveButton("保存") { d, _ ->
                    try {
                        val list = loadQuickButtons().toMutableList()
                        val idx = list.indexOfFirst { it.id == btn.id }
                        if (idx >= 0) {
                            list[idx] = btn.copy(name = nameInput.text.toString(), targetActivity = targetInput.text.toString())
                            saveQuickButtons(list)
                            refreshPanelContent(act)
                        }
                    } catch (e: Throwable) { WeLogger.e(TAG, "保存按钮异常", e) }
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Throwable) { WeLogger.e(TAG, "showQuickButtonEditor 异常", e) }
    }

    // ==================== 工具方法 ====================

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun isDarkMode(act: Activity): Boolean {
        return try {
            (act.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        } catch (e: Throwable) { false }
    }

    private fun dp(value: Int, ctx: Context): Int = try {
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics).toInt()
    } catch (e: Throwable) { value }

    private fun getStatusBarHeight(act: Activity): Int {
        return try {
            val resourceId = act.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) act.resources.getDimensionPixelSize(resourceId) else 0
        } catch (e: Throwable) { 0 }
    }

    private fun showToast(text: String) {
        try {
            val app = currentApplication() ?: return
            Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) { WeLogger.w(TAG, "showToast 失败", e) }
    }

    // ==================== 数据获取 ====================

    private fun fetchWeatherData(): WeatherData? {
        val apiUrl = weatherApiUrl
        if (apiUrl.isBlank()) return null
        val city = if (weatherCity.isBlank()) weatherFallbackCity else weatherCity
        val now = System.currentTimeMillis()
        if (cachedWeather != null && now - lastWeatherFetchTime < weatherRefreshInterval * 1000L) return cachedWeather
        return try {
            val json = httpGet(apiUrl, weatherApiKey) ?: return cachedWeather
            val data = JSONObject(json).optJSONObject("data") ?: JSONObject(json)
            val weather = WeatherData(
                city = data.optString("city", city),
                temperature = data.optString("wendu", "--"),
                feelsLike = data.optString("ganmao", "--"),
                tempHigh = data.optString("high", "--"),
                tempLow = data.optString("low", "--"),
                humidity = data.optString("shidu", "--"),
                windSpeed = data.optString("fengli", "--"),
                weather = data.optString("type", ""),
                updateTime = try { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) } catch (_: Throwable) { "" },
                weatherIcon = data.optString("type", "")
            )
            cachedWeather = weather; lastWeatherFetchTime = now; weather
        } catch (e: Throwable) {
            WeLogger.e(TAG, "获取天气数据失败", e)
            cachedWeather
        }
    }

    private fun fetchQuoteText(): String {
        if (useSignature) {
            return try {
                WePrefs.getString("${PREFS_PREFIX}signature_cache")?.ifBlank { quoteFallback } ?: quoteFallback
            } catch (e: Throwable) { quoteFallback }
        }
        if (quoteApiUrl.isBlank()) return quoteFallback
        return try {
            val json = httpGet(quoteApiUrl, quoteApiKey) ?: return quoteFallback
            JSONObject(json).optString("data", quoteFallback)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "获取语录失败", e)
            quoteFallback
        }
    }

    private fun fetchDailyQuote(): String {
        if (dailyQuoteApiUrl.isBlank()) return dailyQuoteFallback
        return try {
            val json = httpGet(dailyQuoteApiUrl, dailyQuoteApiKey) ?: return dailyQuoteFallback
            JSONObject(json).optString("data", dailyQuoteFallback)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "获取每日一言失败", e)
            dailyQuoteFallback
        }
    }

    private fun httpGet(urlStr: String, apiKey: String = ""): String? {
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        return try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000; connection.readTimeout = 10000; connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (apiKey.isNotBlank()) {
                connection.setRequestProperty("X-API-Key", apiKey)
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            input = connection.inputStream
            input.bufferedReader().readText()
        } catch (e: Throwable) { null } finally {
            try { input?.close() } catch (_: Throwable) {}
            try { connection?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun getAvatarCacheFile(act: Activity): File {
        val dir = File(act.cacheDir, AVATAR_CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "avatar_cache.png")
    }

    private fun loadWeChatAvatar(act: Activity): Bitmap? {
        val cacheFile = getAvatarCacheFile(act)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                val bmp = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (bmp != null) return bmp
            } catch (e: Throwable) { WeLogger.w(TAG, "头像缓存读取失败", e) }
        }
        // 简化：避免反射拿头像，防止新崩溃（具体策略由项目维护者后续实现）
        return try {
            val selfWxId = WeApi.selfWxId
            if (selfWxId.isNotEmpty()) {
                val avatarUrl = WeDatabaseApi.getAvatarUrl(selfWxId)
                if (avatarUrl.isNotEmpty()) downloadBitmap(avatarUrl) else null
            } else null
        } catch (e: Throwable) { null }
    }

    private fun loadWeChatNickname(act: Activity): String {
        return try {
            val selfWxId = WeApi.selfWxId
            if (selfWxId.isNotEmpty()) WeDatabaseApi.getDisplayName(selfWxId) else ""
        } catch (e: Throwable) { "" }
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        return try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 10000; doInput = true }
            connection.connectTimeout = 10000; connection.readTimeout = 10000
            connection.doInput = true; connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            input = connection.inputStream
            BitmapFactory.decodeStream(input)
        } catch (e: Throwable) { null } finally {
            try { input?.close() } catch (_: Throwable) {}
            try { connection?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun startActivityByName(context: Context, className: String) {
        if (className.isBlank()) return
        try {
            val intent = Intent().apply {
                setClassName(context.packageName, className)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "启动Activity失败: $className", e)
            showToast("无法打开该功能")
        }
    }

    // ==================== 设置入口 ====================

    override fun onClick(context: ComponentActivity) {
        if (!BuildConfig.BEAUTIFY_ENABLED) {
            showToast("侧边栏功能编译开关已关闭")
            return
        }
        try {
            val stateHolder = booleanArrayOf(masterEnabled)
            val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20, context), dp(12, context), dp(20, context), dp(12, context)) }
            val swRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val swLabel = TextView(context).apply {
                text = "启用侧边栏"; textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sw = Switch(context).apply {
                isChecked = stateHolder[0]
                setOnCheckedChangeListener { _, c -> stateHolder[0] = c }
            }
            swRow.addView(swLabel); swRow.addView(sw); container.addView(swRow)

            val desc = TextView(context).apply {
                text = "开启后在微信主页左上角显示唤起按钮，点击可打开侧边栏面板"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8, context) }
            }
            container.addView(desc)

            AlertDialog.Builder(context)
                .setTitle("微信主页侧滑侧边栏")
                .setView(container)
                .setPositiveButton("保存") { d, _ ->
                    try {
                        val newEnabled = stateHolder[0]
                        if (masterEnabled != newEnabled) {
                            masterEnabled = newEnabled
                            if (newEnabled) onEnable() else onDisable()
                        }
                    } catch (e: Throwable) { WeLogger.e(TAG, "保存开关异常", e) }
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Throwable) { WeLogger.e(TAG, "onClick 异常", e) }
    }

    // ==================== 常量 ====================

    private const val TAG = "HomeSidePanel"
    private const val PREFS_PREFIX = "hsp_"
    private const val AVATAR_CACHE_DIR = "home_side_panel"
}
