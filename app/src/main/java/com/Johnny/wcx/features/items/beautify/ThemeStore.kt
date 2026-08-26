package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.NinePatchDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.fs.createDirsSafe
import com.tencent.mm.ui.widget.MMNeat7extView
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * 主题商店（v245 全新编写）：
 * - 入口：wcx 设置 → 设置 → 微信主题 → 主题商城
 * - 六款内置主题：Hello Kitty（花朵气泡）/ 小熊（miko 小狗边框气泡）/ 线条小狗（加油鸭鸭气泡）/
 *   吉伊卡哇（花朵气泡）/ 美食小狗（面包🍞气泡）/ 小兔子（猫咪气泡）
 * - 使用主题后全局沉浸式：状态栏/导航栏透明，壁纸悬浮层铺满全屏（含状态栏/导航栏区域），
 *   微信主页、设置页、朋友圈等所有微信页面一律生效；**扫一扫页面不覆盖壁纸**
 *   （仅取消壁纸覆盖，沉浸式等其余不变，避免相机取景区域被遮挡影响扫码）
 * - 气泡按「自定义消息气泡」的方式应用：主题气泡写入 <模块数据>/assets/left_bubble.9.png（对方）
 *   与 right_bubble.9.png（自己），9-patch 黑边解析为 NinePatchDrawable，同时开启该功能开关
 * - 壁纸透明度 5%~100% 滑杆调节（即时生效）
 * - 恢复默认主题：还原系统栏/壁纸层与应用主题前的气泡（应用前自动备份用户原有气泡）
 * - 素材内置在模块 APK assets/theme_store/（壁纸 + 每主题 left/right 气泡）
 */
@Feature(
    name = "主题商城",
    categories = ["界面美化"],
    description = "六款卡通主题一键应用：全局沉浸壁纸（主页/设置/朋友圈等全部页面，扫一扫除外）+ 对应主题气泡，支持透明度调节与恢复默认主题"
)
object ThemeStore : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    private const val TAG = "ThemeStore"
    private const val WALLPAPER_TAG = "wcx_theme_store_wallpaper"
    private const val LEFT_BUBBLE_FILE = "left_bubble.9.png"   // 对方
    private const val RIGHT_BUBBLE_FILE = "right_bubble.9.png" // 自己
    private const val CUSTOM_BUBBLES_PREF_KEY = "自定义消息气泡"

    // ══════════════════════════════════════════════════════════
    // 持久化
    // ══════════════════════════════════════════════════════════
    private var activeThemeId by prefOption("theme_store_active_id", "")
    private var overlayOpacityPct by prefOption("theme_store_overlay_opacity", 40)

    // ══════════════════════════════════════════════════════════
    // 主题定义
    // ══════════════════════════════════════════════════════════
    private data class ThemeInfo(
        val id: String,
        val name: String,
        val wallpaperAsset: String,
        val bubbleOtherAsset: String, // 对方气泡（left）
        val bubbleSelfAsset: String,  // 自己气泡（right）
        val previewColor: Long
    )

    private val builtinThemes = listOf(
        ThemeInfo(
            id = "hellokitty", name = "Hello Kitty",
            wallpaperAsset = "theme_store/wallpaper/hellokitty.jpg",
            bubbleOtherAsset = "theme_store/bubbles/hellokitty/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/hellokitty/right_bubble.9.png",
            previewColor = 0xFFFFB6C1L
        ),
        ThemeInfo(
            id = "bear", name = "小熊",
            wallpaperAsset = "theme_store/wallpaper/bear.jpg",
            bubbleOtherAsset = "theme_store/bubbles/bear/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/bear/right_bubble.9.png",
            previewColor = 0xFFD7A76FL
        ),
        ThemeInfo(
            id = "puppy", name = "线条小狗",
            wallpaperAsset = "theme_store/wallpaper/puppy.jpg",
            bubbleOtherAsset = "theme_store/bubbles/puppy/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/puppy/right_bubble.9.png",
            previewColor = 0xFFFFE082L
        ),
        ThemeInfo(
            id = "chiikawa", name = "吉伊卡哇",
            wallpaperAsset = "theme_store/wallpaper/chiikawa.jpg",
            bubbleOtherAsset = "theme_store/bubbles/chiikawa/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/chiikawa/right_bubble.9.png",
            previewColor = 0xFFA8D8F0L
        ),
        ThemeInfo(
            id = "food_puppy", name = "美食小狗",
            wallpaperAsset = "theme_store/wallpaper/food_puppy.jpg",
            bubbleOtherAsset = "theme_store/bubbles/food_puppy/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/food_puppy/right_bubble.9.png",
            previewColor = 0xFFF7E0B0L
        ),
        ThemeInfo(
            id = "bunny", name = "小兔子",
            wallpaperAsset = "theme_store/wallpaper/bunny.jpg",
            bubbleOtherAsset = "theme_store/bubbles/bunny/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/bunny/right_bubble.9.png",
            previewColor = 0xFFFAD0DCL
        )
    )

    private fun builtinTheme(id: String): ThemeInfo? = builtinThemes.firstOrNull { it.id == id }

    /** 当前生效的主题（内置主题）。 */
    private fun currentTheme(): ThemeInfo? =
        builtinThemes.firstOrNull { it.id == activeThemeId }

    // ══════════════════════════════════════════════════════════
    // 素材目录（模块数据镜像）
    // ══════════════════════════════════════════════════════════
    private val themeStoreDir by lazy { (KnownPaths.moduleData / "theme_store").createDirsSafe() }
    private val builtinDir by lazy { (themeStoreDir / "builtin").createDirsSafe() }
    private val backupBubbleDir by lazy { (themeStoreDir / "backup_bubbles").createDirsSafe() }

    private fun builtinThemeDir(theme: ThemeInfo) = builtinDir / theme.id

    private fun builtinWallpaperFile(theme: ThemeInfo) = builtinThemeDir(theme) / theme.wallpaperAsset.substringAfterLast('/')

    private fun builtinBubbleFile(theme: ThemeInfo, sideName: String) = builtinThemeDir(theme) / sideName

    // ══════════════════════════════════════════════════════════
    // 沉浸式窗口备份
    // ══════════════════════════════════════════════════════════
    private class WindowBackup(
        val actRef: WeakReference<Activity>,
        val statusBarColor: Int,
        val navBarColor: Int,
        val sysUi: Int,
        val contrastStatusEnforced: Boolean,
        val contrastNavEnforced: Boolean
    )

    private val windowBackups = ConcurrentHashMap<Int, WindowBackup>()

    // ══════════════════════════════════════════════════════════
    // 生命周期
    // ══════════════════════════════════════════════════════════
    @Volatile private var callbacksRegistered = false

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {
            if (activeThemeId.isNotEmpty()) {
                try { applyThemeTo(activity) } catch (e: Throwable) { WeLogger.w(TAG, "onActivityResumed 应用主题异常", e) }
            }
        }
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            windowBackups.remove(System.identityHashCode(activity))
        }
    }

    override fun onEnable() {
        registerCallbacks(true)
        WeChatMessageViewApi.addListener(this)
        if (activeThemeId.isNotEmpty()) {
            // 进程重启后恢复：壁纸/沉浸式全局重挂（气泡文件已在 moduleAssets 持久存在）
            applyThemeToAllLive()
            WeLogger.i(TAG, "已恢复主题：$activeThemeId")
        }
    }

    override fun onDisable() {
        restoreDefault()
        registerCallbacks(false)
        WeChatMessageViewApi.removeListener(this)
    }

    private fun registerCallbacks(register: Boolean) {
        try {
            val app = HostApplication ?: return
            if (register && !callbacksRegistered) {
                app.registerActivityLifecycleCallbacks(activityCallbacks)
                callbacksRegistered = true
            } else if (!register && callbacksRegistered) {
                app.unregisterActivityLifecycleCallbacks(activityCallbacks)
                callbacksRegistered = false
            }
        } catch (e: Throwable) { WeLogger.e(TAG, "registerCallbacks 异常", e) }
    }

    private val HostApplication: Application? by lazy {
        runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? Application
        }.getOrNull()
    }

    // ══════════════════════════════════════════════════════════
    // 素材读取（模块 APK 内置 assets → 一次性提取到模块数据目录）
    // ══════════════════════════════════════════════════════════
    /** 从模块 APK assets 读取内置素材；失败返回 null。 */
    private fun readBuiltinAsset(assetPath: String): ByteArray? = try {
        val pkgCtx = HostApplication?.createPackageContext(BuildConfig.APPLICATION_ID, 0)
            ?: return null
        pkgCtx.assets.open(assetPath).use { it.readBytes() }
    } catch (e: Throwable) {
        WeLogger.w(TAG, "读取内置素材失败：$assetPath（$e）")
        null
    }

    /** 确保某个主题的素材已提取到模块数据目录（壁纸 + 两个气泡）。 */
    private fun ensureBuiltinExtracted(theme: ThemeInfo, force: Boolean = false) {
        val dir = builtinThemeDir(theme)
        val entries = listOf(
            theme.wallpaperAsset to builtinWallpaperFile(theme),
            theme.bubbleOtherAsset to builtinBubbleFile(theme, LEFT_BUBBLE_FILE),
            theme.bubbleSelfAsset to builtinBubbleFile(theme, RIGHT_BUBBLE_FILE)
        )
        for ((asset, dest) in entries) {
            if (!force && dest.exists()) continue
            val bytes = readBuiltinAsset(asset) ?: continue
            try {
                dir.createDirsSafe()
                Files.write(dest, bytes)
            } catch (e: Throwable) { WeLogger.w(TAG, "提取素材失败：$asset", e) }
        }
    }

    /** 对话框打开时一次性提取全部内置主题素材（缩略图直接从本地文件加载）。 */
    private fun ensureAllBuiltinExtracted() {
        try { builtinThemes.forEach { ensureBuiltinExtracted(it) } } catch (e: Throwable) { WeLogger.w(TAG, "提取全部素材异常", e) }
    }

    // ══════════════════════════════════════════════════════════
    // 应用主题
    // ══════════════════════════════════════════════════════════
    private fun applyThemeInternal(id: String) {
        val theme = builtinTheme(id) ?: run { WeLogger.w(TAG, "未知主题：$id"); return }
        try {
            ensureBuiltinExtracted(theme)
            backupExistingBubbles()
            // 气泡写入模块 assets（left=对方 / right=自己），并开启「自定义消息气泡」开关
            copySafely(builtinBubbleFile(theme, LEFT_BUBBLE_FILE), KnownPaths.moduleAssets / LEFT_BUBBLE_FILE)
            copySafely(builtinBubbleFile(theme, RIGHT_BUBBLE_FILE), KnownPaths.moduleAssets / RIGHT_BUBBLE_FILE)
            WePrefs.putBool(CUSTOM_BUBBLES_PREF_KEY, true)
            activeThemeId = id
            applyThemeToAllLive()
            WeLogger.i(TAG, "已应用主题：${theme.name}")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "applyThemeInternal 异常", e)
        }
    }

    private fun copySafely(src: java.nio.file.Path, dst: java.nio.file.Path) {
        if (!src.exists()) { WeLogger.w(TAG, "复制源不存在：${src.absolutePathString()}"); return }
        try {
            dst.parent?.createDirsSafe()
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) { WeLogger.w(TAG, "复制失败：${src.name} → ${dst.name}", e) }
    }

    /** 壁纸文件（内置主题提取文件）。 */
    private fun wallpaperSource(): java.nio.file.Path? =
        builtinTheme(activeThemeId)?.let { builtinWallpaperFile(it) }?.takeIf { it.exists() }

    // ══════════════════════════════════════════════════════════
    // 全局应用：对所有存活 Activity 挂载沉浸式 + 壁纸悬浮层
    // ══════════════════════════════════════════════════════════
    private fun applyThemeToAllLive() {
        liveActivities().forEach { act ->
            try { applyThemeTo(act) } catch (e: Throwable) { WeLogger.w(TAG, "应用主题到 ${act.javaClass.simpleName} 异常", e) }
        }
    }

    private fun applyThemeTo(activity: Activity) {
        if (activeThemeId.isEmpty()) return
        applyImmersive(activity)
        // 扫一扫页面不覆盖壁纸：相机取景区域被壁纸遮挡会影响扫码（沉浸式与气泡等其余全部不变）
        if (isScannerPage(activity)) {
            removeWallpaper(activity)
        } else {
            applyWallpaper(activity)
        }
    }

    /** 微信「扫一扫」相关页面：plugin.scanner 包（扫码页/选模式/相册扫码/扫码结果等）。 */
    private fun isScannerPage(activity: Activity): Boolean =
        activity.javaClass.name?.startsWith("com.tencent.mm.plugin.scanner") == true

    /**
     * 沉浸式系统栏：状态栏/导航栏透明 + 浅色图标（可恢复）。
     * 注意：不设置 LAYOUT_FULLSCREEN/LAYOUT_STABLE 布局旗标——那会让微信页面按全屏布局
     * 把内容顶到状态栏下（如「我 → 服务」页面整体上移被遮挡）。透明系统栏 + 壁纸悬浮层
     * 覆盖在 decorView 顶层（含系统栏区域），视觉上同样是全屏沉浸，且不改变页面布局。
     */
    private fun applyImmersive(activity: Activity) {
        val w = activity.window ?: return
        val hash = System.identityHashCode(activity)
        try {
            @Suppress("DEPRECATION")
            if (!windowBackups.containsKey(hash)) {
                windowBackups[hash] = WindowBackup(
                    WeakReference(activity),
                    w.statusBarColor,
                    w.navigationBarColor,
                    w.decorView.systemUiVisibility,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && w.isStatusBarContrastEnforced,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && w.isNavigationBarContrastEnforced
                )
            }
            @Suppress("DEPRECATION")
            w.statusBarColor = AndroidColor.TRANSPARENT
            w.navigationBarColor = AndroidColor.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                w.isStatusBarContrastEnforced = false
                w.isNavigationBarContrastEnforced = false
            }
            // 仅切换图标为浅色（可读），不加任何布局旗标
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = w.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } catch (e: Throwable) {
            WeLogger.w(TAG, "applyImmersive 异常", e)
        }
    }

    /**
     * 壁纸悬浮层：挂在 decorView 顶层（触摸透明），铺满全屏（含状态栏/导航栏区域）。
     * 已存在则复用并刷新壁纸图/透明度，避免闪烁。
     */
    private fun applyWallpaper(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val alpha = overlayOpacityPct.coerceIn(5, 100) / 100f
        val theme = currentTheme()
        val src = wallpaperSource()
        try {
            var container = decor.findViewWithTag<FrameLayout>(WALLPAPER_TAG)
            if (container == null) {
                container = FrameLayout(activity).apply {
                    tag = WALLPAPER_TAG
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                // 保持在最上层，但不盖住之后挂载的侧边栏面板/触发按钮
                decor.addView(container)
            }
            if (decor.indexOfChild(container) != decor.childCount - 1) {
                decor.removeView(container)
                decor.addView(container)
            }
            val iv = container.getChildAt(0) as? ImageView ?: ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }.also { container.addView(it) }
            val bmp = src?.let { runCatching { BitmapFactory.decodeFile(it.absolutePathString()) }.getOrNull() }
            if (bmp != null && iv.tag != src.absolutePathString()) {
                iv.setImageBitmap(bmp)
                iv.tag = src.absolutePathString()
            }
            if (bmp == null) {
                // 壁纸缺失兜底：主题色
                iv.setImageDrawable(ColorDrawable(theme?.previewColor?.toInt() ?: 0xFFF0F0F0.toInt()))
            }
            iv.alpha = alpha
            container.setBackgroundColor(AndroidColor.TRANSPARENT)
        } catch (e: Throwable) {
            WeLogger.w(TAG, "applyWallpaper 异常", e)
        }
    }

    private fun removeWallpaper(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        try {
            decor.findViewWithTag<View>(WALLPAPER_TAG)?.let { decor.removeView(it) }
        } catch (e: Throwable) { WeLogger.w(TAG, "removeWallpaper 异常", e) }
    }

    private fun restoreWindow(activity: Activity) {
        val hash = System.identityHashCode(activity)
        val backup = windowBackups.remove(hash) ?: return
        val act = backup.actRef.get() ?: return
        try {
            @Suppress("DEPRECATION")
            act.window?.statusBarColor = backup.statusBarColor
            act.window?.navigationBarColor = backup.navBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                act.window?.isStatusBarContrastEnforced = backup.contrastStatusEnforced
                act.window?.isNavigationBarContrastEnforced = backup.contrastNavEnforced
            }
            @Suppress("DEPRECATION")
            act.window?.decorView?.systemUiVisibility = backup.sysUi
        } catch (e: Throwable) { WeLogger.w(TAG, "restoreWindow 异常", e) }
    }

    /** 恢复默认：清空主题 id、还原窗口/壁纸层、还原应用主题前的气泡文件。 */
    private fun restoreDefault() {
        try {
            restoreBubbleBackup()
            activeThemeId = ""
            liveActivities().forEach { act ->
                runCatching { removeWallpaper(act) }
                runCatching { restoreWindow(act) }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "restoreDefault 异常", e)
        }
    }

    /** 通过 ActivityThread.mActivities 获取进程内所有存活 Activity。 */
    private fun liveActivities(): List<Activity> = try {
        val atClass = Class.forName("android.app.ActivityThread")
        val at = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
        val activitiesField = atClass.getDeclaredField("mActivities")
        activitiesField.isAccessible = true
        val activities = activitiesField.get(at) as? Map<*, *> ?: return emptyList()
        buildList {
            for (record in activities.values) {
                val activity = record?.javaClass?.getDeclaredField("activity")
                    ?.apply { isAccessible = true }
                    ?.get(record) as? Activity ?: continue
                if (activity.isFinishing || activity.isDestroyed) continue
                add(activity)
            }
        }
    } catch (e: Throwable) {
        WeLogger.d(TAG, "liveActivities 失败：${e.message}")
        emptyList()
    }

    /** 更新所有存活页面已挂载壁纸层的透明度（滑杆即时生效）。 */
    private fun updateAlphaOnLive(alpha: Float) {
        liveActivities().forEach { act ->
            try {
                val decor = act.window?.decorView as? ViewGroup ?: return@forEach
                (decor.findViewWithTag<FrameLayout>(WALLPAPER_TAG)?.getChildAt(0) as? ImageView)?.alpha = alpha
            } catch (e: Throwable) { WeLogger.d(TAG, "更新透明度失败：${e.message}") }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 气泡备份/恢复（应用主题前先备份用户原有气泡，恢复默认时还原）
    // ══════════════════════════════════════════════════════════
    private fun backupExistingBubbles() {
        try {
            if (backupBubbleDir.listDirectoryEntries().isNotEmpty()) return // 已有备份则保留
            val left = KnownPaths.moduleAssets / LEFT_BUBBLE_FILE
            val right = KnownPaths.moduleAssets / RIGHT_BUBBLE_FILE
            if (left.exists()) Files.copy(left, backupBubbleDir / LEFT_BUBBLE_FILE, StandardCopyOption.REPLACE_EXISTING)
            if (right.exists()) Files.copy(right, backupBubbleDir / RIGHT_BUBBLE_FILE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) { WeLogger.w(TAG, "备份气泡异常", e) }
    }

    private fun restoreBubbleBackup() {
        try {
            val left = backupBubbleDir / LEFT_BUBBLE_FILE
            val right = backupBubbleDir / RIGHT_BUBBLE_FILE
            if (left.exists()) Files.copy(left, KnownPaths.moduleAssets / LEFT_BUBBLE_FILE, StandardCopyOption.REPLACE_EXISTING)
            if (right.exists()) Files.copy(right, KnownPaths.moduleAssets / RIGHT_BUBBLE_FILE, StandardCopyOption.REPLACE_EXISTING)
            runCatching { backupBubbleDir.toFile().deleteRecursively() }
        } catch (e: Throwable) { WeLogger.w(TAG, "还原气泡异常", e) }
    }

    // ══════════════════════════════════════════════════════════
    // 气泡直接应用：聊天消息视图创建时按主题气泡替换背景（9-patch）
    // ══════════════════════════════════════════════════════════
    override fun onCreateView(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam, view: View) {
        if (activeThemeId.isEmpty()) return
        try {
            val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
            @Suppress("DEPRECATION")
            when (msgInfo.type) {
                MessageType.TEXT, MessageType.LINK, MessageType.GROUP_NOTE, MessageType.QUOTE -> {
                    val bubble = view.findViewWhich<MMNeat7extView> { it is MMNeat7extView } ?: return
                    applyBubble(bubble, msgInfo.isSelfSender)
                }
                MessageType.VOIP -> {
                    val bubbleView = view.findViewWhich<LinearLayout> {
                        it.javaClass == LinearLayout::class.java &&
                            it.tag?.javaClass?.name?.startsWith("com.tencent.mm.ui.chatting.viewitems") == true
                    } ?: return
                    val bubbleTextView = bubbleView.findViewWhich<TextView> { it is TextView } ?: return
                    applyBubble(bubbleTextView, msgInfo.isSelfSender)
                }
                else -> { /* 其它消息类型不换气泡 */ }
            }
        } catch (e: Throwable) {
            WeLogger.w(TAG, "onCreateView 应用气泡异常", e)
        }
    }

    /** 按「自定义消息气泡」的 9-patch 方式应用：left=对方 / right=自己。 */
    private fun applyBubble(bubbleView: View, isSelfSender: Boolean) {
        val fileName = if (isSelfSender) RIGHT_BUBBLE_FILE else LEFT_BUBBLE_FILE
        val file = KnownPaths.moduleAssets / fileName
        if (!file.exists()) return
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePathString()) }.getOrNull() ?: return
        try {
            val paddingLeft = bubbleView.paddingLeft
            val paddingTop = bubbleView.paddingTop
            val paddingRight = bubbleView.paddingRight
            val paddingBottom = bubbleView.paddingBottom

            val inner = Bitmap.createBitmap(bitmap, 1, 1, bitmap.width - 2, bitmap.height - 2)
            val hRanges = getRanges(bitmap, z = false, z2 = false)   // 左右两条黑边
            val vRanges = getRanges(bitmap, z = true, z2 = false)    // 上下两条黑边
            val topRange = getRanges(bitmap, z = true, z2 = true).firstOrNull()
            val bottomRange = getRanges(bitmap, z = false, z2 = true).firstOrNull()
            val rect = Rect(
                hRanges.firstOrNull()?.start ?: 0,
                topRange?.start ?: 0,
                bitmap.width - 2 - (hRanges.lastOrNull()?.end ?: 0),
                bitmap.height - 2 - (bottomRange?.end ?: 0)
            )
            val byteBuffer = ByteBuffer.allocate((vRanges.size + hRanges.size) * 8 + 68).apply {
                order(ByteOrder.nativeOrder())
                put(1.toByte())
                put((hRanges.size * 2).toByte())
                put((vRanges.size * 2).toByte())
                put(9.toByte())
                putInt(0)
                putInt(0)
                putInt(rect.left)
                putInt(rect.right)
                putInt(rect.top)
                putInt(rect.bottom)
                putInt(0)
                for (r in hRanges) { putInt(r.start); putInt(r.end) }
                for (r in vRanges) { putInt(r.start); putInt(r.end) }
                repeat(9) { putInt(1) }
            }
            val np = NinePatchDrawable(bubbleView.resources, inner, byteBuffer.array(), rect, null)
            val pressed = np.constantState?.newDrawable()?.mutate()?.apply {
                val hsv = FloatArray(3)
                AndroidColor.colorToHSV(0xFF000000.toInt(), hsv)
                hsv[2] *= 0.8f
                setTint(AndroidColor.HSVToColor(hsv))
            }
            val stateList = StateListDrawable().apply {
                if (pressed != null) {
                    addState(intArrayOf(android.R.attr.state_pressed), pressed)
                    addState(intArrayOf(android.R.attr.state_focused), pressed)
                }
                addState(intArrayOf(), np)
            }
            bubbleView.apply {
                background = stateList
                setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
            }
        } catch (e: Throwable) {
            WeLogger.w(TAG, "applyBubble 异常", e)
        }
    }

    private data class Range(val start: Int, val end: Int)

    private fun getRanges(bitmap: Bitmap, z: Boolean, z2: Boolean): ArrayList<Range> {
        val width = if (z) bitmap.width else bitmap.height
        val i = width - 1
        var i2 = -1
        return ArrayList<Range>().apply {
            for (i3 in 1 until i) {
                val pixel = if (z && z2) {
                    bitmap.getPixel(i3, bitmap.height - 1)
                } else if (z) {
                    bitmap.getPixel(i3, 0)
                } else if (z2) {
                    bitmap.getPixel(bitmap.width - 1, i3)
                } else {
                    bitmap.getPixel(0, i3)
                }
                val iAlpha = AndroidColor.alpha(pixel)
                val iRed = AndroidColor.red(pixel)
                val iGreen = AndroidColor.green(pixel)
                val iBlue = AndroidColor.blue(pixel)
                if (iAlpha == 255 && iRed == 0 && iGreen == 0 && iBlue == 0) {
                    if (i2 == -1) i2 = i3 - 1
                } else if (i2 != -1) {
                    add(Range(i2, i3 - 1))
                    i2 = -1
                }
            }
            if (i2 != -1) add(Range(i2, width - 2))
        }
    }

    

    // ══════════════════════════════════════════════════════════
    // 设置 UI：主题商城（入口：wcx 设置 → 设置 → 微信主题）
    // ══════════════════════════════════════════════════════════
    override fun onClick(context: ComponentActivity) {
        try {
            ensureAllBuiltinExtracted()
            showComposeDialog(context) {
                var currentId by remember { mutableStateOf(activeThemeId) }
                var opacity by remember { mutableStateOf(overlayOpacityPct.coerceIn(5, 100)) }
                AlertDialogContent(
                    title = { Text("主题商城") },
                    text = {
                        DefaultColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text("六款卡通主题：一键应用全局沉浸壁纸与对应气泡", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            builtinThemes.forEach { theme ->
                                ThemeRow(
                                    theme = theme,
                                    active = currentId == theme.id,
                                    onApply = {
                                        applyThemeInternal(theme.id)
                                        currentId = theme.id
                                        updateAlphaOnLive(opacity / 100f)
                                    }
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "壁纸透明度 ${opacity}%",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Slider(
                                    value = opacity.toFloat(),
                                    onValueChange = {
                                        opacity = it.toInt().coerceIn(5, 100)
                                        overlayOpacityPct = opacity
                                        updateAlphaOnLive(opacity / 100f)
                                    },
                                    valueRange = 5f..100f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row {
                                TextButton(onClick = {
                                    restoreDefault()
                                    currentId = ""
                                    showToast(context, "已恢复默认主题")
                                }) { Text("恢复默认主题") }
                            }
                        }
                    },
                    dismissButton = { TextButton(onDismiss) { Text("取消") } },
                    confirmButton = {
                        Button(onClick = onDismiss) { Text("关闭") }
                    }
                )
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "onClick 异常", e)
            showToast(context, "主题商城打开失败")
        }
    }

    @Composable
    private fun ThemeRow(theme: ThemeInfo, active: Boolean, onApply: () -> Unit) {
        val thumbFile = remember(theme.id) { builtinWallpaperFile(theme).toFile().takeIf { it.exists() } }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ComposeColor(theme.previewColor))
            ) {
                if (thumbFile != null) {
                    AsyncImage(
                        model = thumbFile,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().size(44.dp)
                    )
                }
            }
            Text(
                text = theme.name + (if (active) "（使用中）" else ""),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            )
            Button(onClick = onApply) { Text(if (active) "已应用" else "应用") }
        }
    }
}