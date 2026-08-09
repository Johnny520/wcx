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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    name = "微信主页侧滑侧边栏",
    categories = ["界面美化"],
    description = "在微信主页左侧添加侧滑面板：头像/签名/天气/每日一言/4 槽快捷"
)
object HomeSidePanelFeature : ClickableFeature() {

    override val alwaysEnabled = false
    override val noSwitchWidget = false

    // ==================== 视图引用 ====================
    @Volatile private var triggerView: View? = null
    @Volatile private var panelRootView: View? = null
    @Volatile private var attachedActivity: Activity? = null

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
                    if (attachedActivity === activity || triggerView != null) {
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
        override fun onFragmentHiddenChanged(fm: FragmentManager, f: Fragment, hidden: Boolean) {
            try { updateVisibility() } catch (e: Throwable) { WeLogger.e(TAG, "onFragmentHiddenChanged 异常", e) }
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
     * - 在 LauncherUI 首页 Tab：创建或保留侧滑栏
     * - 在 LauncherUI 其它 Tab / 其它 Activity：立即 removeView
     */
    private fun updateVisibility() {
        val act = attachedActivity ?: return
        if (!masterEnabled) {
            removeAllViews()
            return
        }
        val shouldShow = isHomePageActive(act)
        if (shouldShow) {
            if (triggerView == null && panelRootView == null) {
                attachTriggerButton(act)
            }
        } else {
            if (triggerView != null || panelRootView != null) {
                removeAllViews()
            }
        }
    }
    @Volatile private var callbacksRegistered = false

    // ==================== 主开关 ====================
    private var masterEnabled by WePrefs.prefOption("${PREFS_PREFIX}master", false)

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
    var quoteApiUrl by WePrefs.prefOption("${PREFS_PREFIX}quote_api_url", "https://api.03c3.cn/api/yl")
    var quoteApiKey by WePrefs.prefOption("${PREFS_PREFIX}quote_api_key", "")
    var quoteRefreshInterval by WePrefs.prefOption("${PREFS_PREFIX}quote_refresh_interval", 3600)
    var quoteFallback by WePrefs.prefOption("${PREFS_PREFIX}quote_fallback", "每一天都是新的开始")

    // ==================== 状态行 ====================
    var onlineStatus by WePrefs.prefOption("${PREFS_PREFIX}online_status", "在线")
    var isOnline by WePrefs.prefOption("${PREFS_PREFIX}is_online", true)

    // ==================== 天气配置 ====================
    var weatherCity by WePrefs.prefOption("${PREFS_PREFIX}weather_city", "北京")
    var weatherApiUrl by WePrefs.prefOption("${PREFS_PREFIX}weather_api_url", "https://api.03c3.cn/api/weather")
    var weatherApiKey by WePrefs.prefOption("${PREFS_PREFIX}weather_api_key", "")
    var weatherRefreshInterval by WePrefs.prefOption("${PREFS_PREFIX}weather_refresh_interval", 1800)
    var weatherFallbackCity by WePrefs.prefOption("${PREFS_PREFIX}weather_fallback_city", "北京")

    // ==================== 每日一言配置 ====================
    var dailyQuoteApiUrl by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_api_url", "https://api.03c3.cn/api/yl")
    var dailyQuoteApiKey by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_api_key", "")
    var dailyQuoteRefreshInterval by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_refresh_interval", 3600)
    var dailyQuoteFallback by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_fallback", "生活不止眼前的苟且，还有诗和远方")

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
            val parsed = Json.decodeFromString<List<SlotConfig>>(json)
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

    // ==================== 生命周期入口 ====================
    override fun onEnable() {
        if (!BuildConfig.BEAUTIFY_ENABLED) {
            WeLogger.w(TAG, "侧边栏功能编译开关已关闭，跳过启用"); return
        }
        masterEnabled = true
        registerActivityCallbacks()
    }

    override fun onDisable() {
        masterEnabled = false
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
private fun isHomePageActive(act: Activity): Boolean = try {
    if (act.isFinishing) return false
    if (act.javaClass.name != "com.tencent.mm.ui.LauncherUI") return false
    val fragAct = act as? FragmentActivity ?: return true  // 不是 FragmentActivity 时，仅靠 Activity 校验
    val current = fragAct.supportFragmentManager.fragments.find { it.isResumed && it.isVisible && !it.isHidden }
        ?: return false
    isHomeTabClass(current.javaClass.name)
} catch (e: Throwable) { WeLogger.e(TAG, "isHomePageActive 异常", e); false }

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

    private fun findLauncherUI(): Activity? = try {
        val atClass = Class.forName("android.app.ActivityThread")
        val at = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
        val activitiesField = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
        val activities = activitiesField.get(at) as? Map<*, *> ?: return null
        for (record in activities.values) {
            val activity = record?.javaClass?.getDeclaredField("activity")
                ?.apply { isAccessible = true }?.get(record) as? Activity ?: continue
            if (isLauncherUI(activity)) return activity
        }
        null
    } catch (_: Throwable) { null }

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

    // ==================== 视图管理（依赖 decorView）====================
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

    private fun showPanel(act: Activity) {
        if (panelRootView != null) return
        val decorView = act.window?.decorView as? ViewGroup ?: return
        try {
            val dark = isDarkMode(act)
            val panel = FrameLayout(act).apply { tag = "home_side_panel_overlay" }
            val scrim = View(act).apply {
                setBackgroundColor(AndroidColor.parseColor("#66000000"))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setOnClickListener { try { hidePanel() } catch (e: Throwable) { WeLogger.e(TAG, "scrim 异常", e) } }
            }
            panel.addView(scrim)
            val container = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(dp(280, act), ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.START or Gravity.TOP }
                setBackgroundColor(AndroidColor.parseColor(if (dark) "#1E1E1E" else "#F5F5F5"))
            }
            panel.addView(container)
            val sbH = getStatusBarHeight(act)
            val headerBar = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16, act), sbH, dp(8, act), dp(8, act)) }
            }
            val title = TextView(act).apply {
                text = "侧边栏"; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(AndroidColor.parseColor(if (dark) "#E0E0E0" else "#212121"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnSettings = makeIconButton(act, "⚙", dp(40, act)) {
                try { showPanelSettingsDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "设置按钮异常", e); showToast("配置面板打开失败：" + e.message) }
            }
            val btnClose = makeIconButton(act, "✕", dp(40, act)) {
                try { hidePanel() } catch (e: Throwable) { WeLogger.e(TAG, "关闭按钮异常", e) }
            }
            btnSettings.setTextColor(AndroidColor.parseColor(if (dark) "#64B5F6" else "#1976D2"))
            btnClose.setTextColor(AndroidColor.parseColor(if (dark) "#AAAAAA" else "#999999"))
            headerBar.addView(title); headerBar.addView(btnSettings); headerBar.addView(btnClose)
            container.addView(headerBar)
            val divider = View(act).apply {
                setBackgroundColor(AndroidColor.parseColor(if (dark) "#3A3A3A" else "#E0E0E0"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            }
            container.addView(divider)
            val scroll = ScrollView(act).apply {
                isFillViewport = true
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val content = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12, act), dp(8, act), dp(12, act), dp(24, act)) }
            }
            scroll.addView(content)
            container.addView(scroll)
            val panelLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (panel.parent != null) (panel.parent as? ViewGroup)?.removeView(panel)
            decorView.addView(panel, panelLp)
            panelRootView = panel
            content.tag = "wcx_panel_content"
            hideTrigger()
            fillPanelContent(act, content)
        } catch (e: Throwable) { WeLogger.e(TAG, "showPanel 异常", e); try { removePanelInternal() } catch (_: Throwable) {} }
    }

    private fun makeIconButton(act: Activity, char: String, sizePx: Int, onClick: () -> Unit): TextView {
        return TextView(act).apply {
            text = char; textSize = 20f; gravity = Gravity.CENTER
            width = sizePx; height = sizePx
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            background = makeRippleBg(AndroidColor.parseColor("#22000000"))
        }
    }

    private fun makeRippleBg(color: Int): android.graphics.drawable.Drawable {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            RippleDrawable(android.content.res.ColorStateList.valueOf(color), null, null)
        } else GradientDrawable().apply { setColor(color) }
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
            if (tv != null && tv.parent != null) (tv.parent as? ViewGroup)?.removeView(tv)
        } catch (e: Throwable) { WeLogger.e(TAG, "removeAllViews 异常", e) }
    }

    // ==================== 面板内容填充 ====================
    private fun fillPanelContent(act: Activity, content: LinearLayout) {
        try {
            content.removeAllViews()
            if (headerEnabled) {
                try { content.addView(makeHeaderCard(act)) } catch (e: Throwable) { WeLogger.e(TAG, "头部卡片异常", e) }
            }
            if (weatherEnabled) {
                try { content.addView(makeWeatherCard(act)) } catch (e: Throwable) { WeLogger.e(TAG, "天气卡片异常", e) }
            }
            if (quickButtonsEnabled) {
                try { content.addView(makeQuickButtonsGrid(act)) } catch (e: Throwable) { WeLogger.e(TAG, "快捷栏异常", e) }
            }
            try { fillFeatureList(act, content) } catch (e: Throwable) { WeLogger.e(TAG, "功能列表异常", e) }
            if (dailyQuoteEnabled) {
                try { content.addView(makeQuoteCard(act)) } catch (e: Throwable) { WeLogger.e(TAG, "每日一言异常", e) }
            }
        } catch (e: Throwable) { WeLogger.e(TAG, "fillPanelContent 异常", e) }
    }

    // ==================== 头部卡片（头像 + 签名）====================
    private fun makeHeaderCard(act: Activity): View {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg(act)
            setPadding(dp(16, act), dp(16, act), dp(16, act), dp(16, act))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { val m = dp(8, act); setMargins(0, m, 0, m) }
        }
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val avatar = ImageView(act).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56, act), dp(56, act))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AndroidColor.parseColor(if (isDarkMode(act)) "#555555" else "#E0E0E0"))
            }
            contentDescription = "头像"
            tag = "wcx_header_avatar"
            // 长按头像 → 弹配置；单击无动作
            isLongClickable = true
            setOnLongClickListener {
                try { showAvatarConfigDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "头像配置异常", e); showToast("头像配置失败：" + e.message) }
                true
            }
        }
        val infoCol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12, act) }
        }
        val statusRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setOnLongClickListener {
                try { showStatusConfigDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "状态配置异常", e); showToast("状态配置失败：" + e.message) }
                true
            }
        }
        val dot = View(act).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8, act), dp(8, act))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AndroidColor.parseColor(if (isOnline) "#4CAF50" else "#9E9E9E")) }
        }
        val statusText = TextView(act).apply {
            text = onlineStatus; textSize = 13f
            setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#AAAAAA" else "#999999"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6, act) }
        }
        statusRow.addView(dot); statusRow.addView(statusText)
        infoCol.addView(statusRow)

        val nickname = TextView(act).apply {
            text = ""; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#E0E0E0" else "#212121"))
            setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4, act) }
            tag = "wcx_header_nickname"
        }
        infoCol.addView(nickname)

        val timeText = TextView(act).apply {
            text = try { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) } catch (_: Throwable) { "00:00" }
            textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#E0E0E0" else "#212121"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            tag = "wcx_header_time"
        }
        infoCol.addView(timeText)

        val dateText = TextView(act).apply {
            text = try { SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE).format(Date()) } catch (_: Throwable) { "" }
            textSize = 12f
            setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#888888" else "#999999"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2, act) }
            tag = "wcx_header_date"
        }
        infoCol.addView(dateText)

        val refreshBtn = TextView(act).apply {
            text = "↻"; textSize = 18f; gravity = Gravity.CENTER
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

        // 签名/语录（长按配置）
        val quote = TextView(act).apply {
            val qText = try { fetchQuoteText() } catch (_: Throwable) { quoteFallback }
            text = qText
            textSize = 13f
            setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#888888" else "#999999"))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8, act) }
            isLongClickable = true
            setOnLongClickListener {
                try { showSignatureConfigDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "签名配置异常", e); showToast("签名配置失败：" + e.message) }
                true
            }
        }
        card.addView(quote)

        // 异步加载头像 + 昵称
        mainHandler.post {
            try {
                val bmp = loadAvatarBitmap(act)
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
                    val bmp = loadAvatarBitmap(act)
                    if (bmp != null) avatar.setImageBitmap(bmp)
                    nickname.text = loadWeChatNickname(act)
                } catch (e: Throwable) { WeLogger.e(TAG, "刷新头像失败", e) }
            }
            timeText.text = try { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) } catch (_: Throwable) { timeText.text }
            dateText.text = try { SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE).format(Date()) } catch (_: Throwable) { dateText.text }
            showToast("已刷新")
        } catch (e: Throwable) { WeLogger.e(TAG, "refreshHeader 异常", e) }
    }

    // ==================== 天气卡片（长按配置）====================
    private fun makeWeatherCard(act: Activity): View {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg(act)
            setPadding(dp(16, act), dp(16, act), dp(16, act), dp(16, act))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { val m = dp(8, act); setMargins(0, m, 0, m) }
            // 仅长按触发配置，单击无动作
            isLongClickable = true
            setOnLongClickListener {
                try { showWeatherConfigDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "天气配置异常", e); showToast("天气配置失败：" + e.message) }
                true
            }
        }
        val configured = weatherApiUrl.isNotBlank()
        card.alpha = if (configured) 1f else 0.4f

        if (!configured) {
            val txt = TextView(act).apply {
                text = "长按卡片配置天气 API 地址"
                textSize = 14f
                setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#AAAAAA" else "#999999"))
                gravity = Gravity.CENTER
            }
            card.addView(txt)
            return card
        }

        val row1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val cityCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val city = TextView(act).apply { text = "--"; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#E0E0E0" else "#212121")) }
        val update = TextView(act).apply { text = ""; textSize = 11f; setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#888888" else "#999999")) }
        cityCol.addView(city); cityCol.addView(update)
        val icon = TextView(act).apply { text = "☁"; textSize = 28f; setTextColor(AndroidColor.parseColor("#1976D2")) }
        row1.addView(cityCol); row1.addView(icon)
        card.addView(row1)

        val row2 = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
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

    // ==================== 4 槽快捷栏（固定 4 个，支持替换）====================
    private fun makeQuickButtonsGrid(act: Activity): View {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            background = makeCardBg(act)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { val m = dp(8, act); setMargins(0, m, 0, m) }
            setPadding(dp(8, act), dp(8, act), dp(8, act), dp(8, act))
        }
        val slots = loadQuickSlots().take(4)
        for (slot in slots) {
            val item = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4, act), 0, dp(4, act), 0) }
                isClickable = true; isFocusable = true
                background = makeRippleBg(AndroidColor.parseColor("#11000000"))
                setOnClickListener {
                    try { executeSlotTarget(act, slot) } catch (e: Throwable) { WeLogger.e(TAG, "slot ${slot.slotId} 点击异常", e) }
                }
                setOnLongClickListener {
                    try { showSlotPickerDialog(act, slot.slotId, slot) } catch (e: Throwable) { WeLogger.e(TAG, "slot ${slot.slotId} 选择异常", e); showToast("快捷栏选择失败：" + e.message) }
                    true
                }
            }
            val iconBg = FrameLayout(act).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40, act), dp(40, act))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AndroidColor.parseColor("#E3F2FD")) }
            }
            val icon = TextView(act).apply {
                text = iconPool[slot.iconName] ?: "+"; textSize = 22f
                setTextColor(AndroidColor.parseColor("#1976D2")); gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            iconBg.addView(icon)
            val label = TextView(act).apply {
                text = slot.name; textSize = 11f
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

    private fun fillFeatureList(act: Activity, container: LinearLayout) {
        data class Entry(val icon: String, val label: String, val onClick: () -> Unit)
        val entries = mutableListOf<Entry>()
        if (momentsEntryEnabled) entries.add(Entry("♥", "朋友圈") { startActivityByName(act, "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI") })
        if (videoEntryEnabled) entries.add(Entry("▶", "视频号") { startActivityByName(act, "com.tencent.mm.plugin.finder.ui.FinderHomeUI") })
        if (clearUnreadEnabled) entries.add(Entry("✓", "清空未读") {
            try { Toast.makeText(act, "已尝试清空未读（占位）", Toast.LENGTH_SHORT).show() } catch (e: Throwable) { WeLogger.e(TAG, "清空未读异常", e) }
        })
        if (wcxSettingsEnabled) entries.add(Entry("⚙", "WCX 设置") {
            try {
                val intent = Intent(act, Class.forName("com.Johnny.wcx.activity.settings.SettingsActivity")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                act.startActivity(intent)
            } catch (e: Throwable) { WeLogger.e(TAG, "WCX 设置启动异常", e); showToast("无法打开WCX设置") }
        })
        val customs = loadCustomFeatures()
        customs.forEach { cf ->
            entries.add(Entry(iconPool[cf.iconName] ?: "+", cf.name) {
                if (cf.isCustomIntent) {
                    try {
                        val intent = Intent().apply { setClassName(act.packageName, cf.targetActivity); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        act.startActivity(intent)
                    } catch (e: Throwable) { WeLogger.e(TAG, "自定义Intent异常", e); showToast("无法打开该功能") }
                } else startActivityByName(act, cf.targetActivity, false)
            })
        }
        entries.forEach { e ->
            val item = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { val m = dp(2, act); setMargins(0, m, 0, m) }
                setPadding(dp(16, act), dp(12, act), dp(16, act), dp(12, act))
                background = makeRippleBg(AndroidColor.parseColor("#11000000"))
                setOnClickListener { e.onClick() }
            }
            val icon = TextView(act).apply {
                text = e.icon; textSize = 20f; setTextColor(AndroidColor.parseColor("#1976D2"))
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

    // ==================== 每日一言卡片（长按配置）====================
    private fun makeQuoteCard(act: Activity): View {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg(act)
            setPadding(dp(16, act), dp(16, act), dp(16, act), dp(16, act))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { val m = dp(8, act); setMargins(0, m, 0, m) }
            isLongClickable = true
            setOnLongClickListener {
                try { showDailyQuoteConfigDialog(act) } catch (e: Throwable) { WeLogger.e(TAG, "每日一言配置异常", e); showToast("每日一言配置失败：" + e.message) }
                true
            }
        }
        val titleRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val q = TextView(act).apply { text = "❝"; textSize = 20f; setTextColor(AndroidColor.parseColor("#1976D2")) }
        val t = TextView(act).apply {
            text = "每日一言（长按配置）"; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#E0E0E0" else "#212121"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6, act) }
        }
        titleRow.addView(q); titleRow.addView(t); card.addView(titleRow)

        val text = TextView(act).apply {
            text = try { fetchDailyQuote() } catch (_: Throwable) { dailyQuoteFallback }
            textSize = 13f
            setTextColor(AndroidColor.parseColor(if (isDarkMode(act)) "#BBBBBB" else "#666666"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8, act) }
        }
        card.addView(text)
        return card
    }

    private fun makeCardBg(act: Activity): android.graphics.drawable.Drawable {
        val dark = isDarkMode(act)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12, act).toFloat()
            setColor(AndroidColor.parseColor(if (dark) "#2C2C2C" else "#FFFFFF"))
        }
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
            addSwitchRow(container, act, "头部卡片（头像/签名/状态）", headerEnabled) { headerEnabled = it }
            addSwitchRow(container, act, "天气卡片", weatherEnabled) { weatherEnabled = it }
            addSwitchRow(container, act, "快捷功能栏", quickButtonsEnabled) { quickButtonsEnabled = it }
            addSwitchRow(container, act, "每日一言卡片", dailyQuoteEnabled) { dailyQuoteEnabled = it }

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
            val panel = panelRootView ?: return
            val scroll = findScrollView(panel)
            val content = scroll?.getChildAt(0) as? LinearLayout ?: return
            fillPanelContent(act, content)
        } catch (e: Throwable) { WeLogger.e(TAG, "refreshPanelContent 异常", e) }
    }

    private fun findScrollView(root: View): ScrollView? {
        if (root is ScrollView) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findScrollView(root.getChildAt(i))?.let { return it }
        return null
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
            val sw = Switch(act).apply {
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
            container.addView(EditText(act).apply { setText(urlHolder[0]); hint = "天气 API 地址" })
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
    private fun fetchWeatherData(): WeatherData? {
        val apiUrl = weatherApiUrl
        if (apiUrl.isBlank()) return null
        val city = if (weatherCity.isBlank()) weatherFallbackCity else weatherCity
        val now = System.currentTimeMillis()
        if (cachedWeather != null && now - lastWeatherFetchTime < weatherRefreshInterval * 1000L) return cachedWeather
        return try {
            val json = httpGet(apiUrl, weatherApiKey) ?: return cachedWeather
            val data = org.json.JSONObject(json).optJSONObject("data") ?: org.json.JSONObject(json)
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
            WeLogger.e(TAG, "获取天气失败", e)
            cachedWeather
        }
    }

    /** 签名/语录文案（3 模式分发） */
    private fun fetchQuoteText(): String {
        return try {
            when (quoteMode) {
                0 -> fetchWeChatSignature()  // 微信原生签名
                1 -> quoteManual.ifBlank { quoteFallback }  // 手动
                2 -> {  // API+Key
                    val url = quoteApiUrl
                    if (url.isBlank()) quoteFallback
                    else {
                        val json = httpGet(url, quoteApiKey) ?: quoteFallback
                        try {
                            org.json.JSONObject(json).optString("data", quoteFallback)
                        } catch (_: Throwable) { quoteFallback }
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
    private fun fetchDailyQuote(): String {
        val url = dailyQuoteApiUrl
        if (url.isBlank()) return dailyQuoteFallback
        return try {
            val json = httpGet(url, dailyQuoteApiKey) ?: return dailyQuoteFallback
            org.json.JSONObject(json).optString("data", dailyQuoteFallback)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "fetchDailyQuote 失败", e)
            dailyQuoteFallback
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
                        if (f.exists() && f.canRead()) BitmapFactory.decodeFile(path) else loadWeChatAvatar(act)
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
                val bmp = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (bmp != null) return bmp
            } catch (e: Throwable) { WeLogger.w(TAG, "头像缓存读取失败", e) }
        }
        return try {
            val selfWxId = WeApi.selfWxId
            if (selfWxId.isNotEmpty()) {
                val avatarUrl = WeDatabaseApi.getAvatarUrl(selfWxId)
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
            }
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else { input = connection.inputStream; BitmapFactory.decodeStream(input) }
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
                text = "开启后在微信主页左上角显示唤起按钮，点击可打开侧滑面板。\n面板内可配置：头像/签名/天气/每日一言/快捷功能 4 槽。\n长按对应卡片可触发配置弹窗。"
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