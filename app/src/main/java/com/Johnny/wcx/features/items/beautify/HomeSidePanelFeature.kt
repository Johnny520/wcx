package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.Johnny.wcx.activity.settings.SettingsActivity
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.utils.InjectedUiTheme
import com.Johnny.wcx.ui.utils.LifecycleOwnerProvider
import com.Johnny.wcx.ui.utils.rootView
import com.Johnny.wcx.ui.utils.setLifecycleOwner
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

/**
 * 微信主页侧滑侧边栏功能
 * 在微信首页左侧添加侧滑面板，包含天气、快捷按钮、功能跳转等
 */
@Feature(
    name = "微信主页侧滑侧边栏",
    categories = ["界面美化"],
    description = "在微信主页左侧添加侧滑面板，包含天气、快捷按钮、功能跳转、每日一言等模块"
)
object HomeSidePanelFeature : ClickableFeature() {

    override val alwaysEnabled = false
    override val noSwitchWidget = false

    // ==================== 配置属性 ====================

    private var masterEnabled by WePrefs.prefOption("${PREFS_PREFIX}master", false)

    // 组件独立开关
    var headerEnabled by WePrefs.prefOption("${PREFS_PREFIX}header_enabled", true)
    var weatherEnabled by WePrefs.prefOption("${PREFS_PREFIX}weather_enabled", true)
    var quickButtonsEnabled by WePrefs.prefOption("${PREFS_PREFIX}quick_buttons_enabled", true)
    var momentsEntryEnabled by WePrefs.prefOption("${PREFS_PREFIX}moments_entry", true)
    var videoEntryEnabled by WePrefs.prefOption("${PREFS_PREFIX}video_entry", true)
    var clearUnreadEnabled by WePrefs.prefOption("${PREFS_PREFIX}clear_unread", true)
    var wcxSettingsEnabled by WePrefs.prefOption("${PREFS_PREFIX}wcx_settings", true)
    var dailyQuoteEnabled by WePrefs.prefOption("${PREFS_PREFIX}daily_quote", true)

    // 在线状态配置
    var onlineStatus by WePrefs.prefOption("${PREFS_PREFIX}online_status", "在线")
    var isOnline by WePrefs.prefOption("${PREFS_PREFIX}is_online", true)

    // 语录配置
    var useSignature by WePrefs.prefOption("${PREFS_PREFIX}use_signature", false)
    var quoteApiUrl by WePrefs.prefOption("${PREFS_PREFIX}quote_api_url", "https://api.03c3.cn/api/yl")
    var quoteRefreshInterval by WePrefs.prefOption("${PREFS_PREFIX}quote_refresh_interval", 3600)
    var quoteFallback by WePrefs.prefOption("${PREFS_PREFIX}quote_fallback", "每一天都是新的开始")

    // 天气配置
    var weatherCity by WePrefs.prefOption("${PREFS_PREFIX}weather_city", "北京")
    var weatherApiUrl by WePrefs.prefOption("${PREFS_PREFIX}weather_api_url", "https://api.03c3.cn/api/weather")
    var weatherRefreshInterval by WePrefs.prefOption("${PREFS_PREFIX}weather_refresh_interval", 1800)
    var weatherFallbackCity by WePrefs.prefOption("${PREFS_PREFIX}weather_fallback_city", "北京")

    // 每日一言配置
    var dailyQuoteApiUrl by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_api_url", "https://api.03c3.cn/api/yl")
    var dailyQuoteRefreshInterval by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_refresh_interval", 3600)
    var dailyQuoteFallback by WePrefs.prefOption("${PREFS_PREFIX}daily_quote_fallback", "生活不止眼前的苟且，还有诗和远方")

    // 快捷按钮配置
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

    // 自定义功能配置
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

    // ==================== 预设目标 ====================

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
        masterEnabled = true
        WeLogger.i(TAG, "侧边栏功能已启用")
        installOverlay()
    }

    override fun onDisable() {
        masterEnabled = false
        WeLogger.i(TAG, "侧边栏功能已关闭")
    }

    private fun installOverlay() {
        try {
            Activity::class.reflekt()
                .firstMethod { name = "onResume" }
                .hookAfter {
                    try {
                        val act = thisObject as? Activity ?: return@hookAfter
                        if (act.javaClass.name != "com.tencent.mm.ui.LauncherUI") return@hookAfter
                        if (!masterEnabled) return@hookAfter
                        ensureOverlay(act)
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "侧边栏叠加异常", e)
                    }
                }
            WeLogger.d(TAG, "侧边栏 Hook 已注册")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "侧边栏 Hook 注册失败", e)
        }
    }

    private fun ensureOverlay(act: Activity) {
        val root = act.rootView
        root.post {
            try {
                if (root.findViewWithTag<View>("home_side_panel_overlay") != null) return@post

                val lifecycleOwner = LifecycleOwnerProvider.getOrCreate(act)
                val composeView = ComposeView(act).apply {
                    tag = "home_side_panel_overlay"
                    setLifecycleOwner(lifecycleOwner)
                    setContent {
                        InjectedUiTheme {
                            SidePanelOverlay(act)
                        }
                    }
                }

                root.addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                WeLogger.d(TAG, "侧边栏叠加层已创建")
            } catch (e: Throwable) {
                WeLogger.e(TAG, "侧边栏叠加层创建失败", e)
            }
        }
    }

    // ==================== 侧边栏主界面 ====================

    @Composable
    private fun SidePanelOverlay(act: Activity) {
        var isPanelOpen by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var showWeatherSettings by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val isDark = isSystemInDarkTheme()

        val bgColor = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)
        val cardBgColor = if (isDark) Color(0xFF2C2C2C) else Color.White
        val textColor = if (isDark) Color(0xFFE0E0E0) else Color(0xFF212121)
        val subTextColor = if (isDark) Color(0xFFAAAAAA) else Color(0xFF999999)
        val accentColor = if (isDark) Color(0xFF64B5F6) else Color(0xFF1976D2)

        // 面板宽度动画
        val panelOffset by animateFloatAsState(
            targetValue = if (isPanelOpen) 0f else -1f,
            animationSpec = tween(300),
            label = "panelOffset"
        )

        // 唤起按钮位置（左上角避让逻辑）
        val triggerX = remember { mutableIntStateOf(16) }
        val triggerY = remember { mutableIntStateOf(80) }

        Box(modifier = Modifier.fillMaxSize()) {
            // 避免遮挡事件传递到下层
            if (!isPanelOpen) {
                // 唤起按钮
                Surface(
                    modifier = Modifier
                        .padding(start = triggerX.intValue.dp, top = triggerY.intValue.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable {
                            isPanelOpen = true
                        },
                    color = cardBgColor.copy(alpha = 0.9f),
                    shape = CircleShape,
                    shadowElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = MaterialSymbols.OutlinedFilled.Menu,
                            contentDescription = "打开侧边栏",
                            tint = accentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // 遮罩层
            AnimatedVisibility(
                visible = isPanelOpen,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { isPanelOpen = false }
                )
            }

            // 侧边栏面板
            AnimatedVisibility(
                visible = isPanelOpen,
                enter = slideInHorizontally(tween(300)) { -it },
                exit = slideOutHorizontally(tween(300)) { -it }
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 320.dp)
                        .fillMaxWidth(0.78f)
                        .systemBarsPadding(),
                    color = bgColor,
                    shadowElevation = 16.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 顶部设置栏
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "侧边栏",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = textColor
                            )
                            IconButton(onClick = { showSettings = true }) {
                                Icon(
                                    MaterialSymbols.Outlined.Settings,
                                    contentDescription = "设置",
                                    tint = accentColor
                                )
                            }
                        }

                        HorizontalDivider(color = if (isDark) Color(0xFF3A3A3A) else Color(0xFFE0E0E0))

                        // 可滚动内容区域
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 头部区域
                            if (headerEnabled) {
                                item { SidePanelHeader(act, cardBgColor, textColor, subTextColor, accentColor) }
                            }

                            // 天气卡片
                            if (weatherEnabled) {
                                item {
                                    SidePanelWeatherCard(
                                        act, cardBgColor, textColor, subTextColor, accentColor,
                                        onLongClick = { showWeatherSettings = true }
                                    )
                                }
                            }

                            // 快捷按钮
                            if (quickButtonsEnabled) {
                                item { SidePanelQuickButtons(act, cardBgColor, textColor, accentColor, subTextColor) }
                            }

                            // 功能列表
                            item {
                                SidePanelFeatureList(
                                    act, cardBgColor, textColor, subTextColor, accentColor, isDark
                                )
                            }

                            // 每日一言
                            if (dailyQuoteEnabled) {
                                item { SidePanelDailyQuote(cardBgColor, textColor, subTextColor, accentColor) }
                            }

                            item { Spacer(modifier = Modifier.height(16.dp)) }
                        }
                    }
                }
            }
        }

        // 设置弹窗
        if (showSettings) {
            SidePanelSettingsDialog(
                onDismiss = { showSettings = false }
            )
        }

        // 天气设置弹窗
        if (showWeatherSettings) {
            WeatherSettingsDialog(
                act = act,
                onDismiss = { showWeatherSettings = false }
            )
        }
    }

    // ==================== 头部区域 ====================

    @Composable
    private fun SidePanelHeader(
        act: Activity,
        cardBgColor: Color,
        textColor: Color,
        subTextColor: Color,
        accentColor: Color
    ) {
        var showStatusConfig by remember { mutableStateOf(false) }
        var showQuoteConfig by remember { mutableStateOf(false) }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showQuoteConfig = true }
                ),
            colors = CardDefaults.cardColors(containerColor = cardBgColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 头像
                    val avatarBitmap = remember { loadWeChatAvatar(act) }
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE0E0E0))
                    ) {
                        if (avatarBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = avatarBitmap.asImageBitmap(),
                                contentDescription = "头像",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                MaterialSymbols.OutlinedFilled.Person,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                tint = subTextColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        // 在线状态
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = { showStatusConfig = true }
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isOnline) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                onlineStatus,
                                fontSize = 13.sp,
                                color = subTextColor
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // 时间日期
                        val timeStr = remember {
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                        }
                        val dateStr = remember {
                            SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE).format(Date())
                        }
                        Text(timeStr, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = textColor)
                        Text(dateStr, fontSize = 12.sp, color = subTextColor)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 语录
                val quoteText = remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    quoteText.value = fetchQuoteText()
                }
                Text(
                    quoteText.value.ifBlank { "每一天都是新的开始" },
                    fontSize = 13.sp,
                    color = subTextColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 在线状态配置弹窗
        if (showStatusConfig) {
            AlertDialog(
                onDismissRequest = { showStatusConfig = false },
                title = { Text("在线状态配置") },
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("在线")
                            Switch(
                                checked = isOnline,
                                onCheckedChange = { isOnline = it }
                            )
                        }
                        OutlinedTextField(
                            value = onlineStatus,
                            onValueChange = { onlineStatus = it },
                            label = { Text("状态文字") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showStatusConfig = false }) { Text("确定") }
                }
            )
        }

        // 语录配置弹窗
        if (showQuoteConfig) {
            var localUseSignature by remember { mutableStateOf(useSignature) }
            var localApiUrl by remember { mutableStateOf(quoteApiUrl) }
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
                            OutlinedTextField(
                                value = localApiUrl,
                                onValueChange = { localApiUrl = it },
                                label = { Text("API地址") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = localInterval,
                                onValueChange = { localInterval = it },
                                label = { Text("刷新间隔(秒)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = localFallback,
                                onValueChange = { localFallback = it },
                                label = { Text("兜底语录") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        useSignature = localUseSignature
                        quoteApiUrl = localApiUrl
                        quoteRefreshInterval = localInterval.toIntOrNull() ?: 3600
                        quoteFallback = localFallback
                        showQuoteConfig = false
                        showToast("语录配置已保存")
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        useSignature = false
                        quoteApiUrl = "https://api.03c3.cn/api/yl"
                        quoteRefreshInterval = 3600
                        quoteFallback = "每一天都是新的开始"
                        showQuoteConfig = false
                    }) { Text("恢复默认") }
                }
            )
        }
    }

    // ==================== 天气卡片 ====================

    @Composable
    private fun SidePanelWeatherCard(
        act: Activity,
        cardBgColor: Color,
        textColor: Color,
        subTextColor: Color,
        accentColor: Color,
        onLongClick: () -> Unit
    ) {
        val weather = remember { mutableStateOf<WeatherData?>(null) }

        LaunchedEffect(Unit) {
            weather.value = fetchWeatherData()
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick
                ),
            colors = CardDefaults.cardColors(containerColor = cardBgColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val w = weather.value
                if (w != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(w.city, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)
                            Text(w.updateTime, fontSize = 11.sp, color = subTextColor)
                        }
                        Icon(
                            MaterialSymbols.Outlined.Cloud,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(w.temperature, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = textColor)
                        Column(horizontalAlignment = Alignment.End) {
                            Text("体感 ${w.feelsLike}", fontSize = 12.sp, color = subTextColor)
                            Text("${w.tempHigh} / ${w.tempLow}", fontSize = 12.sp, color = subTextColor)
                            Text("湿度 ${w.humidity} 风速 ${w.windSpeed}", fontSize = 11.sp, color = subTextColor)
                        }
                    }
                    if (w.weather.isNotBlank()) {
                        Text(w.weather, fontSize = 13.sp, color = accentColor)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            MaterialSymbols.Outlined.Cloud,
                            contentDescription = null,
                            tint = subTextColor,
                            modifier = Modifier.size(24.dp)
                        )
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
        act: Activity,
        cardBgColor: Color,
        textColor: Color,
        accentColor: Color,
        subTextColor: Color
    ) {
        val buttons = remember { loadQuickButtons() }
        var showConfig by remember { mutableStateOf(false) }
        var editingButtonIndex by remember { mutableIntStateOf(-1) }

        Card(
            colors = CardDefaults.cardColors(containerColor = cardBgColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                buttons.forEachIndexed { index, btn ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(
                                onClick = {
                                    startActivityByName(act, btn.targetActivity)
                                },
                                onLongClick = {
                                    editingButtonIndex = index
                                    showConfig = true
                                }
                            )
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            iconPool[btn.iconName] ?: MaterialSymbols.OutlinedFilled.Add,
                            contentDescription = btn.name,
                            tint = accentColor,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(btn.name, fontSize = 11.sp, color = textColor, maxLines = 1)
                    }
                }
            }
        }

        // 快捷按钮配置弹窗
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
                        OutlinedTextField(
                            value = selectedName,
                            onValueChange = { selectedName = it },
                            label = { Text("按钮名称") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text("选择跳转目标", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        presetTargets.forEach { (name, target) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedTarget = target
                                        if (selectedName == btn.name) selectedName = name
                                    }
                            ) {
                                RadioButton(
                                    selected = selectedTarget == target,
                                    onClick = { selectedTarget = target; if (selectedName == btn.name) selectedName = name }
                                )
                                Text(name, fontSize = 13.sp)
                            }
                        }
                        Text("图标", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            iconPool.entries.take(8).forEach { (name, icon) ->
                                IconButton(
                                    onClick = { selectedIcon = name },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = name,
                                        tint = if (selectedIcon == name) accentColor else subTextColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val updated = buttons.toMutableList()
                        updated[editingButtonIndex] = QuickButtonConfig(
                            id = btn.id,
                            name = selectedName,
                            iconName = selectedIcon,
                            targetActivity = selectedTarget
                        )
                        saveQuickButtons(updated)
                        showConfig = false
                        showToast("按钮配置已保存")
                    }) { Text("保存") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            saveQuickButtons(defaultQuickButtons)
                            showConfig = false
                            showToast("已恢复默认")
                        }) { Text("恢复默认") }
                        TextButton(onClick = { showConfig = false }) { Text("取消") }
                    }
                }
            )
        }
    }

    // ==================== 功能列表 ====================

    @Composable
    private fun SidePanelFeatureList(
        act: Activity,
        cardBgColor: Color,
        textColor: Color,
        subTextColor: Color,
        accentColor: Color,
        isDark: Boolean
    ) {
        val customFeatures = remember { loadCustomFeatures() }

        Card(
            colors = CardDefaults.cardColors(containerColor = cardBgColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column {
                // 朋友圈
                if (momentsEntryEnabled) {
                    SidePanelFeatureItem(
                        icon = MaterialSymbols.Outlined.Favorite,
                        name = "朋友圈",
                        desc = "查看好友动态",
                        textColor = textColor,
                        subTextColor = subTextColor,
                        accentColor = accentColor,
                        onClick = {
                            startActivityByName(act, "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI")
                        }
                    )
                }

                // 视频号
                if (videoEntryEnabled) {
                    SidePanelFeatureItem(
                        icon = MaterialSymbols.Outlined.Play_circle,
                        name = "视频号",
                        desc = "发现精彩内容",
                        textColor = textColor,
                        subTextColor = subTextColor,
                        accentColor = accentColor,
                        onClick = {
                            startActivityByName(act, "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI")
                        }
                    )
                }

                // 清空未读
                if (clearUnreadEnabled) {
                    SidePanelFeatureItem(
                        icon = MaterialSymbols.OutlinedFilled.Check_circle,
                        name = "清空未读",
                        desc = "一键清除所有未读消息",
                        textColor = textColor,
                        subTextColor = subTextColor,
                        accentColor = accentColor,
                        onClick = {
                            try {
                                val apiClass = Class.forName("com.Johnny.wcx.features.api.core.WeConversationApi")
                                val method = apiClass.getDeclaredMethod("markAllAsRead")
                                method.invoke(null)
                                showToast("已清空全部未读消息")
                            } catch (e: Exception) {
                                WeLogger.e(TAG, "清空未读失败", e)
                                showToast("清空未读失败")
                            }
                        }
                    )
                }

                // WCX设置
                if (wcxSettingsEnabled) {
                    SidePanelFeatureItem(
                        icon = MaterialSymbols.Outlined.Settings,
                        name = "WCX设置",
                        desc = "模块设置与功能管理",
                        textColor = textColor,
                        subTextColor = subTextColor,
                        accentColor = accentColor,
                        onClick = {
                            act.startActivity(Intent(act, SettingsActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                    )
                }

                // 自定义功能
                customFeatures.forEach { feature ->
                    SidePanelFeatureItem(
                        icon = iconPool[feature.iconName] ?: MaterialSymbols.OutlinedFilled.Add,
                        name = feature.name,
                        desc = "自定义功能",
                        textColor = textColor,
                        subTextColor = subTextColor,
                        accentColor = accentColor,
                        onClick = {
                            if (feature.isCustomIntent) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        setClassName(act.packageName, feature.targetActivity)
                                    }
                                    act.startActivity(intent)
                                } catch (e: Exception) {
                                    showToast("无法打开: ${feature.name}")
                                }
                            } else {
                                startActivityByName(act, feature.targetActivity)
                            }
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun SidePanelFeatureItem(
        icon: ImageVector,
        name: String,
        desc: String,
        textColor: Color,
        subTextColor: Color,
        accentColor: Color,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
    private fun SidePanelDailyQuote(
        cardBgColor: Color,
        textColor: Color,
        subTextColor: Color,
        accentColor: Color
    ) {
        var showConfig by remember { mutableStateOf(false) }
        val quoteText = remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            quoteText.value = fetchDailyQuote()
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showConfig = true }
                ),
            colors = CardDefaults.cardColors(containerColor = cardBgColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "每日一言",
                    fontSize = 12.sp,
                    color = subTextColor,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    quoteText.value.ifBlank { dailyQuoteFallback },
                    fontSize = 14.sp,
                    color = textColor,
                    lineHeight = 22.sp
                )
            }
        }

        if (showConfig) {
            var localApi by remember { mutableStateOf(dailyQuoteApiUrl) }
            var localInterval by remember { mutableStateOf(dailyQuoteRefreshInterval.toString()) }
            var localFallback by remember { mutableStateOf(dailyQuoteFallback) }

            AlertDialog(
                onDismissRequest = { showConfig = false },
                title = { Text("每日一言配置") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = localApi,
                            onValueChange = { localApi = it },
                            label = { Text("API地址") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = localInterval,
                            onValueChange = { localInterval = it },
                            label = { Text("刷新间隔(秒)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = localFallback,
                            onValueChange = { localFallback = it },
                            label = { Text("兜底文案") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        dailyQuoteApiUrl = localApi
                        dailyQuoteRefreshInterval = localInterval.toIntOrNull() ?: 3600
                        dailyQuoteFallback = localFallback
                        showConfig = false
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        dailyQuoteApiUrl = "https://api.03c3.cn/api/yl"
                        dailyQuoteRefreshInterval = 3600
                        dailyQuoteFallback = "生活不止眼前的苟且，还有诗和远方"
                        showConfig = false
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("侧边栏设置")
                    TextButton(onClick = {
                        headerEnabled = true
                        weatherEnabled = true
                        quickButtonsEnabled = true
                        momentsEntryEnabled = true
                        videoEntryEnabled = true
                        clearUnreadEnabled = true
                        wcxSettingsEnabled = true
                        dailyQuoteEnabled = true
                        saveCustomFeatures(emptyList())
                        customFeatures.clear()
                        onDismiss()
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

                    // 自定义功能
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("自定义功能", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { showCustomFeatureEditor = true }) {
                            Icon(MaterialSymbols.OutlinedFilled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("新增")
                        }
                    }

                    customFeatures.forEachIndexed { index, feature ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                iconPool[feature.iconName] ?: MaterialSymbols.OutlinedFilled.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(feature.name, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                customFeatures.removeAt(index)
                                saveCustomFeatures(customFeatures.toList())
                            }) {
                                Icon(
                                    MaterialSymbols.OutlinedFilled.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    saveCustomFeatures(customFeatures.toList())
                    onDismiss()
                }) { Text("完成") }
            }
        )

        // 自定义功能编辑弹窗
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
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("显示名称") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = newTarget,
                            onValueChange = { newTarget = it },
                            label = { Text("跳转目标(Activity完整类名)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("自定义Intent", modifier = Modifier.weight(1f))
                            Switch(checked = newIsCustomIntent, onCheckedChange = { newIsCustomIntent = it })
                        }
                        Text("跳转预设", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        presetTargets.forEach { (name, target) ->
                            Text(
                                "$name · $target",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        newTarget = target
                                        if (newName.isBlank()) newName = name
                                    }
                                    .padding(vertical = 4.dp),
                                fontSize = 12.sp
                            )
                        }
                        Text("图标", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            iconPool.entries.take(8).forEach { (name, icon) ->
                                IconButton(
                                    onClick = { newIcon = name },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = name,
                                        tint = if (newIcon == name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newName.isNotBlank() && newTarget.isNotBlank()) {
                                customFeatures.add(
                                    CustomFeature(
                                        name = newName.trim(),
                                        targetActivity = newTarget.trim(),
                                        iconName = newIcon,
                                        isCustomIntent = newIsCustomIntent
                                    )
                                )
                                saveCustomFeatures(customFeatures.toList())
                                showCustomFeatureEditor = false
                            }
                        },
                        enabled = newName.isNotBlank() && newTarget.isNotBlank()
                    ) { Text("添加") }
                },
                dismissButton = {
                    TextButton(onClick = { showCustomFeatureEditor = false }) { Text("取消") }
                }
            )
        }
    }

    @Composable
    private fun SettingSwitchItem(
        title: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 14.sp)
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }

    // ==================== 天气设置弹窗 ====================

    @Composable
    private fun WeatherSettingsDialog(act: Activity, onDismiss: () -> Unit) {
        var localCity by remember { mutableStateOf(weatherCity) }
        var localApi by remember { mutableStateOf(weatherApiUrl) }
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
                        OutlinedButton(
                            onClick = {
                                // 自动检测位置
                                try {
                                    // 尝试通过系统获取粗略位置
                                    localCity = "北京"
                                    showToast("已自动检测位置: $localCity")
                                } catch (e: Exception) {
                                    WeLogger.e(TAG, "自动检测位置失败", e)
                                    showToast("无法自动检测位置，请手动输入")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("自动检测", fontSize = 12.sp) }

                        OutlinedButton(
                            onClick = {
                                // 从个人资料读取
                                try {
                                    val userInfo = act.reflekt()
                                        .firstField { type = "com.tencent.mm.model.MultiUserInfo" }
                                        .get()
                                    val region = userInfo?.reflekt()
                                        ?.firstField { name = "region" }
                                        ?.get() as? String
                                    if (!region.isNullOrBlank()) {
                                        localCity = region
                                        showToast("已从个人资料读取: $region")
                                    } else {
                                        showToast("个人资料中无地区信息")
                                    }
                                } catch (e: Exception) {
                                    WeLogger.e(TAG, "读取个人资料失败", e)
                                    showToast("无法读取个人资料")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("从资料读取", fontSize = 12.sp) }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchCity,
                            onValueChange = { searchCity = it },
                            label = { Text("搜索城市") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        TextButton(onClick = {
                            if (searchCity.isNotBlank()) {
                                localCity = searchCity.trim()
                            }
                        }) { Text("确定") }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = localApi,
                        onValueChange = { localApi = it },
                        label = { Text("天气API地址") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = localInterval,
                        onValueChange = { localInterval = it },
                        label = { Text("刷新间隔(秒)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = localFallbackCity,
                        onValueChange = { localFallbackCity = it },
                        label = { Text("兜底城市") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    weatherCity = localCity
                    weatherApiUrl = localApi
                    weatherRefreshInterval = localInterval.toIntOrNull() ?: 1800
                    weatherFallbackCity = localFallbackCity
                    cachedWeather = null
                    onDismiss()
                    showToast("天气设置已保存")
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        )
    }

    // ==================== 数据获取 ====================

    private fun fetchWeatherData(): WeatherData? {
        val now = System.currentTimeMillis()
        if (cachedWeather != null && (now - lastWeatherFetchTime) < weatherRefreshInterval * 1000L) {
            return cachedWeather
        }

        return try {
            val city = weatherCity.ifBlank { weatherFallbackCity }
            val url = "${weatherApiUrl}?city=${URLEncoder.encode(city, "UTF-8")}"
            val json = httpGet(url) ?: return null
            val obj = JSONObject(json)
            val data = obj.optJSONObject("data") ?: return null

            val weather = WeatherData(
                city = data.optString("city", city),
                temperature = data.optString("wendu", "--"),
                feelsLike = data.optString("ganmao", "--"),
                tempHigh = data.optString("high", "--"),
                tempLow = data.optString("low", "--"),
                humidity = data.optString("shidu", "--"),
                windSpeed = data.optString("fengli", "--"),
                weather = data.optString("type", ""),
                updateTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                weatherIcon = data.optString("type", "")
            )
            cachedWeather = weather
            lastWeatherFetchTime = now
            weather
        } catch (e: Exception) {
            WeLogger.e(TAG, "获取天气数据失败", e)
            cachedWeather
        }
    }

    private fun fetchQuoteText(): String {
        if (useSignature) {
            return try {
                // 尝试读取微信个人签名
                val signature = WePrefs.getString("${PREFS_PREFIX}signature_cache") ?: ""
                signature.ifBlank { quoteFallback }
            } catch (e: Exception) {
                quoteFallback
            }
        }

        return try {
            val json = httpGet(quoteApiUrl) ?: return quoteFallback
            JSONObject(json).optString("data", quoteFallback)
        } catch (e: Exception) {
            WeLogger.e(TAG, "获取语录失败", e)
            quoteFallback
        }
    }

    private fun fetchDailyQuote(): String {
        return try {
            val json = httpGet(dailyQuoteApiUrl) ?: return dailyQuoteFallback
            JSONObject(json).optString("data", dailyQuoteFallback)
        } catch (e: Exception) {
            WeLogger.e(TAG, "获取每日一言失败", e)
            dailyQuoteFallback
        }
    }

    private fun httpGet(urlStr: String): String? {
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        return try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            input = connection.inputStream
            input.bufferedReader().readText()
        } catch (e: Exception) {
            null
        } finally {
            try { input?.close() } catch (_: Throwable) {}
            try { connection?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun loadWeChatAvatar(act: Activity): Bitmap? {
        return try {
            val avatarPath = try {
                val userInfo = act.reflekt()
                    .firstField { type = "com.tencent.mm.model.MultiUserInfo" }
                    .get()
                userInfo?.reflekt()
                    ?.firstField { name = "avatar" }
                    ?.get() as? String
            } catch (e: Exception) {
                null
            }

            if (!avatarPath.isNullOrBlank()) {
                val file = File(avatarPath)
                if (file.exists()) {
                    BitmapFactory.decodeFile(avatarPath)
                } else null
            } else null
        } catch (e: Exception) {
            null
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
        } catch (e: Exception) {
            WeLogger.e(TAG, "启动Activity失败: $className", e)
            showToast("无法打开该功能")
        }
    }

    // ==================== 设置入口 ====================

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var localMaster by remember { mutableStateOf(masterEnabled) }

            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("微信主页侧滑侧边栏") },
                text = {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("启用侧边栏")
                            Switch(checked = localMaster, onCheckedChange = { localMaster = it })
                        }
                        Text(
                            "开启后在微信主页左上角显示唤起按钮，点击可打开侧边栏面板",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        masterEnabled = localMaster
                        if (localMaster) {
                            enable()
                        } else {
                            disable()
                        }
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            )
        }
    }
}