package com.Johnny.wcx.activity.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import top.yukonga.miuix.kmp.basic.Switch
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Tune
import com.composables.icons.materialsymbols.outlined.Category
import com.composables.icons.materialsymbols.outlined.Apps
import com.composables.icons.materialsymbols.outlined.Bolt
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Phone_android
import com.composables.icons.materialsymbols.outlined.Smartphone
import com.composables.icons.materialsymbols.outlined.Sports_esports
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.style.SoftCard
import com.Johnny.wcx.ui.style.SoftDivider
import com.Johnny.wcx.ui.style.WxPalette
import com.Johnny.wcx.utils.AppUpdater
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.UpdateResult
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.Intent
import com.Johnny.wcx.utils.formatEpoch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.border
import com.Johnny.wcx.features.core.BaseFeature
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Code
import com.Johnny.wcx.ui.style.fxReveal
import com.Johnny.wcx.ui.style.rememberGrowNumber
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.TextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.clearText
import com.composables.icons.materialsymbols.outlined.Search
import top.yukonga.miuix.kmp.basic.IconButton
import com.composables.icons.materialsymbols.outlined.Close
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester


// ---------------------------------------------------------------------------
//  Page 0 — Home
// ---------------------------------------------------------------------------

private fun openLsposedManager(context: Context) {
    val managerPackage = "org.lsposed.manager"
    val injectedPackage = "com.android.shell"

    runCatching {
        context.startActivity(
            Intent {
                component = ComponentName(injectedPackage, "$injectedPackage.BugreportWarningActivity")
                addCategory("$managerPackage.LAUNCH_MANAGER")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }.onFailure { WeLogger.e("HomePager", "failed to launch LSPosed manager activity", it) }

    runCatching {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "android.telephony.action.SECRET_CODE"
        } else {
            "android.provider.Telephony.SECRET_CODE"
        }
        context.sendBroadcast(
            Intent(action, "android_secret_code://5776733".toUri()).setPackage("android")
        )
    }.onFailure { WeLogger.e("HomePager", "failed to broadcast LSPosed secret code", it) }
}

/** 安全判断当前是否运行在微信宿主进程内 */
private fun safeIsHost(): Boolean {
    return runCatching { HostInfo.isHost }.getOrDefault(false)
}

/**
 * 安全获取微信版本信息。
 * - 主体App进程：通过 PackageManager 查询（需 Android 11+ queries 声明）
 * - 微信进程内：直接从 HostInfo 读取当前宿主版本
 * 返回 null 表示未检测到微信。
 */
private fun safeGetWeChatVersionInfo(context: Context): String? {
    // 微信进程内：直接从 HostInfo 读取
    if (safeIsHost()) {
        return runCatching {
            val name = HostInfo.versionName.ifEmpty { return null }
            val code = HostInfo.versionCode
            "微信 $name ($code)"
        }.getOrNull()
    }

    // 主体App进程：通过 PackageManager 查询
    return runCatching {
        val pm = context.packageManager ?: return null
        val info = pm.getPackageInfo(PackageNames.WECHAT, 0)
        val name = info.versionName?.ifEmpty { null } ?: return null
        val code = info.longVersionCode
        "微信 $name ($code)"
    }.getOrNull()
}

/**
 * 识别运行环境：LSPosed / LSPatch / 未知。
 * - 主体App进程：主动探测并缓存到 WePrefs（跨进程共享）
 * - 微信进程内：从 WePrefs 读取缓存值，禁止在微信进程内探测
 */
private fun detectOrReadLspEnvironment(context: Context): String {
    // 微信进程内：从跨进程缓存读取
    if (safeIsHost()) {
        return runCatching {
            WePrefs.getStringOrDef(Preferences.CACHED_LSP_ENVIRONMENT, "未知")
        }.getOrDefault("未知")
    }

    // 主体App进程：主动探测并缓存
    val result = runCatching {
        val pm = context.packageManager ?: return@runCatching "未知"

        val hasLsposed = runCatching {
            pm.getPackageInfo("org.lsposed.manager", 0)
        }.isSuccess
        if (hasLsposed) return@runCatching "LSPosed"

        val hasLspatch = runCatching {
            pm.getPackageInfo("org.lspatch.manager", 0)
        }.isSuccess
        if (hasLspatch) return@runCatching "LSPatch"

        "未知"
    }.getOrDefault("未知")

    // 缓存到 WePrefs 供微信进程读取
    runCatching {
        WePrefs.putString(Preferences.CACHED_LSP_ENVIRONMENT, result)
    }.onFailure {
        WeLogger.e("HomePager", "failed to cache LSP environment", it)
    }

    return result
}

/** 读取 LSPosed API 版本（跨进程缓存），返回显示字符串。 */
private fun safeGetLspApiVersion(): String {
    val apiVersion = runCatching {
        WePrefs.getIntOrDef(Preferences.CACHED_LSP_API_VERSION, 0)
    }.getOrDefault(0)
    if (apiVersion <= 0) return "未知"

    val hotReload = if (apiVersion >= 102) " (支持热重载)" else " (需重启)"
    return "API $apiVersion$hotReload"
}

/** 版本字符串格式化：git+4fcbb76 (73) */
private fun formatLocalVersion(): String {
    val name = BuildConfig.VERSION_NAME.ifEmpty { "未知" }
    return "$name (${BuildConfig.VERSION_CODE})"
}

/** 安全打开外部链接，处理无可用浏览器场景 */
private fun safeOpenUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure {
        WeLogger.e("HomePager", "failed to open url: $url", it)
    }
}

@Composable
fun HomePager(
    onOpenFeatures: () -> Unit,
    onOpenModuleHealth: () -> Unit,
    onOpenCategory: (String) -> Unit = {},
    singlePageMode: Boolean = false,
    onToggleSinglePage: (Boolean) -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenScript: () -> Unit = {},
) {
    val context = LocalContext.current
    val allItems = FeaturesProvider.ALL_HOOK_ITEMS
    // 单页模式：功能搜索（分类网格上方）——输入即过滤，点结果直达所属分类
    var featureQuery by remember { mutableStateOf("") }
    val enabledCount = remember {
        allItems.count { WePrefs.getBoolOrFalse(it.name) }
    }
    val totalCount = remember { allItems.size }

    // 点「已启用 / 全部功能」时弹出的清单（就地展示，不跳功能页）
    var quickListKind by remember { mutableStateOf<String?>(null) }

    var latestVersion by remember { mutableStateOf<String?>(null) }
    var isLatest by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(true) }

    // 设备信息：微信版本和运行环境在重组间保持稳定
    val wechatVersion = remember { safeGetWeChatVersionInfo(context) }
    val lspEnvironment = remember { detectOrReadLspEnvironment(context) }
    val lspApiVersion = remember { safeGetLspApiVersion() }

    val scope = rememberCoroutineScope()
    // 抽成函数：首启自动检查一次；激活卡右侧的刷新按钮也能手动触发（检查中防抖）。
    fun checkUpdateNow() {
        if (isChecking) return
        scope.launch {
            isChecking = true
            runCatching {
                when (val result = AppUpdater.checkForUpdate()) {
                    is UpdateResult.UpdateAvailable -> {
                        val tag = result.info.releaseTag.removePrefix("v").ifEmpty { null }
                        latestVersion = tag
                        isLatest = false
                    }
                    is UpdateResult.UpToDate -> {
                        latestVersion = null
                        isLatest = true
                    }
                    is UpdateResult.Error -> {
                        // GitHub 未认证接口是 60 次/小时/IP 共享限流，返回 403 属于正常现象。
                        // 以前这里用 ERROR + 完整堆栈刷屏，用户看日志会误以为模块出错。
                        val cause = result.cause
                        val message = cause?.message.orEmpty()
                        if (message.contains("403") || message.contains("rate limit", true)) {
                            WeLogger.w("HomePager", "检查更新被 GitHub 限流（403），稍后再试")
                        } else {
                            WeLogger.w(
                                "HomePager",
                                "检查更新失败：" + message.ifBlank { cause?.javaClass?.simpleName ?: "未知原因" },
                            )
                        }
                        latestVersion = null
                        isLatest = false
                    }
                }
            }.onFailure {
                WeLogger.e("HomePager", "Update check exception", it)
                latestVersion = null
                isLatest = false
            }
            isChecking = false
        }
    }
    LaunchedEffect(Unit) { checkUpdateNow() }

    Box(modifier = Modifier.fillMaxSize()) {
    MiuixListScaffold(title = "") {
        // ---- 标题区域 ----
        // fillMaxWidth + wrapContentHeight 防止无限高度约束导致灰色渲染
        // background 设置与 TopAppBar 毛玻璃相同的背景色，避免滚动时出现灰色色块
        item {
            Column(
                modifier = Modifier
                    .fxReveal(0)
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(top = 8.dp, start = 18.dp, end = 18.dp, bottom = 2.dp),
            ) {
                Text(
                    text = "WCX",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = WxPalette.textPrimary(),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "微信，解锁超能力，重构你的使用体验",
                    fontSize = 13.sp,
                    color = WxPalette.TextSecondary,
                )
            }
        }

        // ---- 样式开关：经典四 Tab ⇄ 单页 Dashboard ----
        item {
            SoftCard(contentPadding = 12, modifier = Modifier.fxReveal(1)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "单页模式",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = WxPalette.textPrimary(),
                        )
                        Text(
                            text = "隐藏底部导航，功能分类直接铺在主页；关闭则回到四 Tab 导航",
                            fontSize = 11.sp,
                            color = WxPalette.TextSecondary,
                        )
                    }
                    Switch(checked = singlePageMode, onCheckedChange = onToggleSinglePage)
                }
            }
        }

        // ---- 大状态卡片 ----
        item {
            Spacer(Modifier.height(8.dp))
            ActivationCard(latestVersion, isLatest, isChecking, onRefresh = ::checkUpdateNow)
        }

        // ---- 统计：三格彩灯（已启用 / 全部 / 分类），前两格点击弹清单，第三格跳功能页 ----
        item {
            Spacer(Modifier.height(8.dp))
            SoftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CountTile(
                        modifier = Modifier.weight(1f),
                        icon = {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Bolt,
                                contentDescription = null,
                                tint = Color(0xFF12D18E),
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        value = if (com.Johnny.wcx.ui.style.AnimFx.enabled())
                            rememberGrowNumber(enabledCount.toFloat()).roundToInt().toString()
                        else enabledCount.toString(),
                        label = "已启用",
                        onClick = { quickListKind = QUICK_LIST_ENABLED },
                    )
                    CountTile(
                        modifier = Modifier.weight(1f),
                        icon = {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Apps,
                                contentDescription = null,
                                tint = Color(0xFF3D7EFF),
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        value = if (com.Johnny.wcx.ui.style.AnimFx.enabled())
                            rememberGrowNumber(totalCount.toFloat()).roundToInt().toString()
                        else totalCount.toString(),
                        label = "全部功能",
                        onClick = { quickListKind = QUICK_LIST_ALL },
                    )
                    if (!singlePageMode) {
                        CountTile(
                            modifier = Modifier.weight(1f),
                            icon = {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.Category,
                                    contentDescription = null,
                                    tint = Color(0xFF8B5CF6),
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            value = if (com.Johnny.wcx.ui.style.AnimFx.enabled())
                                rememberGrowNumber(
                                    allItems.mapNotNull { it.categories.firstOrNull() }.distinct().size.toFloat(),
                                ).roundToInt().toString()
                            else remember {
                                allItems.mapNotNull { it.categories.firstOrNull() }.distinct().size
                            }.toString(),
                            label = "分类",
                            onClick = onOpenFeatures,
                        )
                    }
                }
            }
        }

        // ---- 单页模式：功能搜索 + 功能分类网格 ----
        if (singlePageMode) {
            item {
                Text(
                    text = "功能分类",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = WxPalette.TextSecondary,
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                )
            }
            item {
                FeatureSearchBox(
                    query = featureQuery,
                    onQueryChange = { featureQuery = it },
                    modifier = Modifier.fxReveal(0),
                )
            }
            if (featureQuery.isBlank()) {
                item { CategoryGrid(onOpenCategory = onOpenCategory) }
            } else {
                item {
                    FeatureSearchResult(
                        query = featureQuery,
                        onOpenCategory = onOpenCategory,
                        modifier = Modifier.fxReveal(1),
                    )
                }
            }
        }

        // ---- 工具：两列快捷瓦片（大图标彩色方块，与整体网格语言一致） ----
        item {
            Text(
                text = "工具",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.TextSecondary,
                modifier = Modifier.padding(start = 6.dp, top = 10.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(
                    icon = MaterialSymbols.Outlined.Build_circle,
                    title = "模块自检",
                    subtitle = "体检 · 定位失效功能",
                    tint = Color(0xFF12D18E),
                    onClick = onOpenModuleHealth,
                    modifier = Modifier.weight(1f),
                )
                QuickTile(
                    icon = MaterialSymbols.Outlined.Sports_esports,
                    title = "脚本插件",
                    subtitle = "本地插件 · 在线市场",
                    tint = Color(0xFF00C2A8),
                    onClick = onOpenScript,
                    modifier = Modifier.weight(1f),
                )
                if (singlePageMode) {
                    QuickTile(
                        icon = MaterialSymbols.Outlined.Code,
                        title = "日志",
                        subtitle = "运行日志与崩溃记录",
                        tint = Color(0xFFFF8A3D),
                        onClick = onOpenLogs,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (singlePageMode) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickTile(
                        icon = MaterialSymbols.Outlined.Tune,
                        title = "设置",
                        subtitle = "界面 · 更新 · 备份恢复",
                        tint = Color(0xFF8B5CF6),
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ---- 设备信息标题 ----
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "设备信息",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.TextSecondary,
                modifier = Modifier.padding(start = 6.dp, top = 4.dp),
            )
        }

        // ---- 设备信息卡片 ----
        item {
            SystemInfoCard(wechatVersion, lspEnvironment, lspApiVersion, !safeIsHost(), modifier = Modifier.fxReveal(3))
        }

        // ---- 相关链接 ----
        item {
            Text(
                text = "相关链接",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.TextSecondary,
                modifier = Modifier.padding(start = 6.dp, top = 10.dp),
            )
        }
        item {
            HomeCard(modifier = Modifier.fxReveal(4)) {
                HomeRow(
                    icon = MaterialSymbols.Outlined.Code,
                    title = "GitHub",
                    subtitle = "Johnny520/wcx",
                    onClick = { safeOpenUrl(context, "https://github.com/Johnny520/wcx") },
                )
                SoftDivider()
                HomeRow(
                    icon = MaterialSymbols.Outlined.Chat,
                    title = "交流群组",
                    subtitle = "https://t.me/wcx12138",
                    onClick = { safeOpenUrl(context, "https://t.me/wcx12138") },
                )
            }
        }

        // ---- 底部留白 ----
        item {
            Spacer(Modifier.height(CONTENT_BOTTOM_INSET))
        }
    }
        val kind = quickListKind
        if (kind != null) {
            FeatureQuickListOverlay(
                title = if (kind == QUICK_LIST_ENABLED) "已启用功能" else "全部功能",
                items = if (kind == QUICK_LIST_ENABLED) {
                    allItems.filter { WePrefs.getBoolOrFalse(it.name) }
                } else {
                    allItems
                },
                onDismiss = { quickListKind = null },
                onPick = { feature ->
                    quickListKind = null
                    onOpenCategory(feature.categories.firstOrNull() ?: "未分类")
                },
            )
        }
    }
}

@Composable
private fun ActivationCard(
    latestVersion: String?,
    isLatest: Boolean,
    isChecking: Boolean,
    onRefresh: () -> Unit = {},
) {
    val context = LocalContext.current
    val accentColor = WxPalette.AccentEnd
    // 检查中时状态胶囊做呼吸脉冲，静止时保持低透明度——一眼看出「正在动」。
    val pulseAnim = remember { Animatable(0.34f) }
    LaunchedEffect(isChecking) {
        if (!isChecking) {
            pulseAnim.snapTo(0.34f)
            return@LaunchedEffect
        }
        while (true) {
            pulseAnim.animateTo(0.9f, tween(1200, easing = FastOutSlowInEasing))
            pulseAnim.animateTo(0.34f, tween(1200, easing = FastOutSlowInEasing))
        }
    }
    val statusText = when {
        isChecking -> "检查中"
        isLatest -> "已是最新"
        latestVersion != null -> "有更新"
        else -> "检查失败"
    }
    val shape = RoundedCornerShape(22.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(WxPalette.cardBrush())
            .border(1.dp, WxPalette.cardBorderBrush(), shape)
            .clickable { openLsposedManager(context) }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ① 状态 + 胶囊
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "状态",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .background(
                        accentColor.copy(alpha = if (isChecking) pulseAnim.value * 0.45f else 0.12f),
                        RoundedCornerShape(50),
                    )
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = accentColor,
                )
            }
        }
        // ② 图标 + 主标题 + 圆形按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Check_circle,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "模块已激活",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = WxPalette.textPrimary(),
                )
                Text(
                    text = formatLocalVersion(),
                    fontSize = 12.sp,
                    color = WxPalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 刷新按钮：点击手动检查更新（检查中禁用防抖）
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(WxPalette.accentBrush())
                    .clickable(enabled = !isChecking) { onRefresh() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Refresh,
                    contentDescription = "检查更新",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        // ③ 细分割线
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(WxPalette.cardBorder())
        )
        // ④ 双栏信息
        Row {
            Column(Modifier.weight(1f)) {
                Text("版本", fontSize = 11.sp, color = WxPalette.TextSecondary)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = BuildConfig.VERSION_NAME,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WxPalette.textPrimary(),
                )
            }
            Column(Modifier.weight(1f)) {
                Text("更新", fontSize = 11.sp, color = WxPalette.TextSecondary)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = statusText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                )
            }
        }
    }
}

/** 纯白圆角卡片（首页分组列表容器）。 */
@Composable
private fun HomeCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(WxPalette.CardRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(WxPalette.cardBrush())
            .border(1.dp, WxPalette.cardBorderBrush(), shape),
        content = content,
    )
}

/** 卡片内一行：圆角图标 + 标题/副标题 + 右箭头。 */
@Composable
private fun HomeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(WxPalette.PrimarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = WxPalette.Primary,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.textPrimary(),
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = WxPalette.TextSecondary,
                )
            }
        }
        Text(
            text = "\u203A",
            fontSize = 20.sp,
            color = WxPalette.TextTertiary,
        )
    }
}

@Composable
private fun TagChip(text: String, color: Color, textColor: Color = color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (textColor == color) color.copy(alpha = 0.1f) else color),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 单页模式的功能搜索：默认只显示一个带放大镜的搜索条，点击才展开输入框（自动聚焦），
 * 点右侧 × 收起并清空。避免「一条长输入框常驻 + 光标常闪」的笨重感。
 */
@Composable
private fun FeatureSearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(query.isNotEmpty()) }
    val focus = remember { FocusRequester() }
    SoftCard(contentPadding = 0, modifier = modifier) {
        if (!expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Search,
                    contentDescription = "搜索",
                    modifier = Modifier.size(18.dp),
                    tint = WxPalette.textSecondary(),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "搜索功能（名称 / 说明）",
                    fontSize = 13.sp,
                    color = WxPalette.textSecondary(),
                )
            }
        } else {
            val state = rememberTextFieldState(query)
            TextField(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                label = "搜索功能（名称 / 说明）",
                leadingIcon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        tint = WxPalette.textSecondary(),
                    )
                },
                trailingIcon = {
                    IconButton(onClick = {
                        state.clearText()
                        onQueryChange("")
                        expanded = false
                    }) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Close,
                            contentDescription = "收起搜索",
                            tint = WxPalette.textSecondary(),
                        )
                    }
                },
            )
            // 文本变化同步到外部状态
            androidx.compose.runtime.LaunchedEffect(state.text.toString()) {
                if (state.text.toString() != query) onQueryChange(state.text.toString())
            }
            LaunchedEffect(expanded) { if (expanded) focus.requestFocus() }
        }
    }
}

/**
 * 单页模式的功能搜索结果：**直接带开关**（与「功能」页一致），点左侧标题进所属分类。
 *
 * 之前单页模式只给一行文字、点了只能跳分类，想开一个功能得先进分类再翻；
 * 这里把开关直接放到结果行上，搜到就能开/关。
 */
@Composable
private fun FeatureSearchResult(
    query: String,
    onOpenCategory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val q = query.trim()
    // 与「功能」页同一口径：当前微信版本不支持的功能不出现
    val matched = remember(q) {
        if (q.isEmpty()) emptyList()
        else FeaturesProvider.ALL_HOOK_ITEMS
            .filter { it.unavailableReason == null }
            .filter { it.name.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true) }
            .take(60)
    }
    val switchStates = remember { mutableStateMapOf<String, Boolean>() }

    // 与 FeaturesPager.CatRow / SettingsActivity.FeatureRow 完全一致的开关流程
    fun toggle(feature: com.Johnny.wcx.features.core.SwitchFeature, requested: Boolean) {
        val unavailable = if (requested) feature.unavailableReason else null
        if (unavailable != null) {
            com.Johnny.wcx.utils.android.showToast(unavailable)
            return
        }
        if (feature.onBeforeToggle(requested, context)) {
            WePrefs.putBool(feature.name, requested)
            feature.isEnabled = requested
            switchStates[feature.name] = requested
        }
    }

    SoftCard(contentPadding = 0, modifier = modifier) {
        Column {
            if (matched.isEmpty()) {
                Text(
                    text = "没有找到匹配「" + q + "」的功能",
                    fontSize = 13.sp,
                    color = WxPalette.textSecondary(),
                    modifier = Modifier.padding(18.dp),
                )
            } else {
                Text(
                    text = "找到 " + matched.size + " 个功能（可直接开关）",
                    fontSize = 11.sp,
                    color = WxPalette.textSecondary(),
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp),
                )
                matched.forEachIndexed { index, feature ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    feature.categories.firstOrNull()?.let { onOpenCategory(it) }
                                }
                                .padding(vertical = 2.dp),
                        ) {
                            Text(
                                text = feature.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = WxPalette.textPrimary(),
                            )
                            if (feature.description.isNotBlank()) {
                                Text(
                                    text = feature.description,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = WxPalette.textSecondary(),
                                    maxLines = 2,
                                )
                            }
                            if (feature.categories.isNotEmpty()) {
                                Text(
                                    text = feature.categories.joinToString(" · "),
                                    fontSize = 10.sp,
                                    color = WxPalette.textSecondary(),
                                )
                            }
                        }
                        val switchable = feature is com.Johnny.wcx.features.core.SwitchFeature &&
                            !(feature is com.Johnny.wcx.features.core.ClickableFeature && feature.noSwitchWidget)
                        if (switchable) {
                            Spacer(Modifier.width(8.dp))
                            val switchFeature = feature as com.Johnny.wcx.features.core.SwitchFeature
                            Switch(
                                checked = switchStates[feature.name] ?: switchFeature.isEnabled,
                                onCheckedChange = { requested -> toggle(switchFeature, requested) },
                            )
                        }
                    }
                    if (index != matched.lastIndex) SoftDivider(modifier = Modifier.padding(start = 14.dp))
                }
            }
        }
    }
}

/** 功能分类网格（单页模式）：4 列彩色圆图标 + 名称 + 启用比例，点进分类详情。 */
@Composable
private fun CategoryGrid(onOpenCategory: (String) -> Unit) {
    SoftCard(contentPadding = 14) {
        FEATURE_CATEGORIES.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { entry ->
                    val colorIndex = FEATURE_CATEGORIES.indexOfFirst { it.first == entry.first }
                    val tint = CATEGORY_COLORS[colorIndex.coerceAtLeast(0) % CATEGORY_COLORS.size]
                    val stat = runCatching {
                        val all = FeaturesProvider.ALL_HOOK_ITEMS.filter { entry.first in it.categories }
                        all.count { WePrefs.getBoolOrFalse(it.name) } to all.size
                    }.getOrDefault(0 to 0)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenCategory(entry.first) }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(50))
                                .background(tint.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = entry.second,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = entry.first,
                            fontSize = 10.sp,
                            color = WxPalette.textPrimary(),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stat.first.toString() + "/" + stat.second,
                            fontSize = 9.sp,
                            color = WxPalette.TextSecondary,
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/** 工具瓦片：彩色圆角图标 + 标题/副标题，按压回弹，2 列网格用。 */
@Composable
private fun QuickTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 620f),
        label = "quickTile",
    )
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(WxPalette.cardBrush())
            .border(1.dp, WxPalette.cardBorderBrush(), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = WxPalette.textPrimary(),
        )
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = WxPalette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CountTile(
    modifier: Modifier,
    icon: @Composable () -> Unit,
    value: String,
    label: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 620f),
        label = "tilePress",
    )
    val tileShape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(tileShape)
            .background(WxPalette.cardBrush())
            .border(1.dp, WxPalette.cardBorderBrush(), tileShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(12.dp),
    ) {
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.CenterStart) {
            icon()
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = value,
            style = TextStyle(
                brush = WxPalette.accentBrush(),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = WxPalette.TextSecondary,
        )
    }
}

@Composable
private fun SystemInfoCard(
    wechatVersion: String?,
    lspEnvironment: String,
    lspApiVersion: String,
    showLspEnvironment: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ① 微信版本
            InfoRow(
                icon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Smartphone,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                },
                title = "微信版本",
                content = wechatVersion ?: "未检测到微信",
                showDivider = true,
            )
            // ② 运行环境 — 仅在主体App进程显示，微信进程内不显示
            if (showLspEnvironment) {
                InfoRow(
                    icon = {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Check_circle,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    title = "运行环境",
                    content = lspEnvironment.ifEmpty { "未知" },
                    showDivider = true,
                )
            }
            // ③ LSPosed API 版本
            if (showLspEnvironment && lspApiVersion != "未知") {
                InfoRow(
                    icon = {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Build_circle,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    title = "LSPosed API",
                    content = lspApiVersion,
                    showDivider = true,
                )
            }
            // ④ 构建时间
            InfoRow(
                icon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Build_circle,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                },
                title = "构建时间",
                content = runCatching { formatEpoch(BuildConfig.BUILD_TIMESTAMP, true) }.getOrDefault("未知"),
                showDivider = true,
            )
            // ⑤ Android 版本
            InfoRow(
                icon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Phone_android,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                },
                title = "Android 版本",
                content = "${Build.VERSION.RELEASE.orEmpty().ifEmpty { "未知" }} (API ${Build.VERSION.SDK_INT})",
                showDivider = true,
            )
            // ⑥ 设备型号
            InfoRow(
                icon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Smartphone,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                },
                title = "设备型号",
                content = "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().ifEmpty { "未知" },
                showDivider = true,
            )
            // ⑦ 系统架构
            InfoRow(
                icon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Smartphone,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                },
                title = "系统架构",
                content = Build.SUPPORTED_ABIS?.joinToString(", ")?.ifEmpty { "未知" } ?: "未知",
                showDivider = false,
            )
        }
    }
}

@Composable
private fun InfoRow(
    icon: @Composable () -> Unit,
    title: String,
    content: String,
    showDivider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(modifier = Modifier.size(24.dp)) {
                icon()
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = content,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 54.dp)
                    .height(0.5.dp)
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f)),
            )
        }
    }
}

// ---------------------------------------------------------------------------
//  功能清单浮层：点「已启用 / 全部功能」原地展开，再点某项直接跳到该分类页
// ---------------------------------------------------------------------------

private const val QUICK_LIST_ENABLED = "enabled"
private const val QUICK_LIST_ALL = "all"

@Composable
private fun FeatureQuickListOverlay(
    title: String,
    items: List<BaseFeature>,
    onDismiss: () -> Unit,
    onPick: (BaseFeature) -> Unit,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    val sheetInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .clickable(interactionSource = scrimInteraction, indication = null, onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(WxPalette.cardBackground())
                .clickable(interactionSource = sheetInteraction, indication = null) {}
                .padding(top = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = WxPalette.textPrimary(),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = items.size.toString() + " 项",
                    fontSize = 12.sp,
                    color = WxPalette.textSecondary(),
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("关闭", fontSize = 13.sp, color = WxPalette.AccentEnd)
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items, key = { it.name }) { feature ->
                    val on = WePrefs.getBoolOrFalse(feature.name)
                    val rowShape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(rowShape)
                            .background(WxPalette.cardBrush())
                            .border(1.dp, WxPalette.cardBorderBrush(), rowShape)
                            .clickable { onPick(feature) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (on) Color(0xFF12D18E) else Color(0x33999999)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = feature.name,
                                fontSize = 14.sp,
                                color = WxPalette.textPrimary(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = feature.categories.firstOrNull() ?: "未分类",
                                fontSize = 11.sp,
                                color = WxPalette.textSecondary(),
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = if (on) "已启用" else "关闭",
                            fontSize = 11.sp,
                            color = if (on) Color(0xFF12D18E) else WxPalette.textSecondary(),
                        )
                        Text(
                            text = "\u203A",
                            fontSize = 18.sp,
                            color = WxPalette.TextTertiary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
