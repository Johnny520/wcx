package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Paint
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
import com.Johnny.wcx.features.api.ui.WeMainActivityBeautifyApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.activity.settings.SettingsActivity
import com.Johnny.wcx.activity.settings.SettingsNavigation
import dev.ujhhgtg.reflekt.reflekt
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
 * - 入口：wcx 设置 → 设置 → 微信主题 → 主题工坊
 * - 六款内置主题：Hello Kitty（花朵气泡）/ 小熊（miko 小狗边框气泡）/ 线条小狗（加油鸭鸭气泡）/
 *   吉伊卡哇（花朵气泡）/ 美食小狗（面包🍞气泡）/ 小兔子（猫咪气泡）
 * - 使用主题后全局沉浸式：状态栏/导航栏透明，壁纸悬浮层铺满全屏（含状态栏/导航栏区域），
 *   微信主页、设置页、朋友圈等所有微信页面一律生效；**扫一扫与查看相册页面不挂壁纸**
 *   （仅取消壁纸覆盖，沉浸式等其余不变，避免相机取景区域被遮挡影响扫码）
 * - 气泡按「自定义消息气泡」的方式应用：主题气泡写入 <模块数据>/assets/left_bubble.9.png（对方）
 *   与 right_bubble.9.png（自己），9-patch 黑边解析为 NinePatchDrawable，同时开启该功能开关
 * - 壁纸透明度 5%~100% 滑杆调节（即时生效）
 * - 恢复默认主题：还原系统栏/壁纸层与应用主题前的气泡（应用前自动备份用户原有气泡）
 * - 素材内置在模块 APK assets/theme_store/（壁纸 + 每主题 left/right 气泡）
 */
@Feature(
    name = "主题工坊",
    categories = ["界面美化"],
    description = "内置 6 款全局主题，全局沉浸壁纸（主页/通讯录/发现/我的 等 4 个界面，或全部界面）+ 对应气泡，支持透明度调节与备份恢复"
)
object ThemeStore : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    private const val TAG = "ThemeStore"
    private const val WALLPAPER_TAG = "wcx_theme_store_wallpaper"
    private const val LEFT_BUBBLE_FILE = "left_bubble.9.png"   // 对方
    private const val RIGHT_BUBBLE_FILE = "right_bubble.9.png" // 自己
    private const val CUSTOM_BUBBLES_PREF_KEY = "自定义消息气泡"

    /**
     * 渲染方式：
     * - background：写 DecorView 的 background。视觉上等价于旧「四 Tab 主题背景」的 DecorView 最底层，
     *   但零子 View、不改任何节点顺序（不会再触发微信 queueIdle 的 NPE）。
     * - overlay：贴在内容容器最上层，一定看得见，但会整体压在界面上。
     */
    private const val RENDER_BACKGROUND = "background"
    private const val RENDER_OVERLAY = "overlay"

    /** 当前功能名（与 @Feature.name 保持一致，初始化期 name 还没注入）。 */
    private const val NEW_FEATURE_NAME = "主题工坊"

    /** 改名前的老开关 key，用于把用户的旧状态迁过来。 */
    private const val LEGACY_FEATURE_NAME = "主题商城"

    init {
        // 功能从「主题商城」改名「主题工坊」。开关在 WePrefs 里以 name 为 key，
        // 这里只清掉旧 key，**不继承旧值** —— 主题总开关必须默认关闭，
        // 要开只能靠用户主动点「应用」（那时才写 true，否则应用完不生效）。
        // 注意：name 由 FeaturesLoader 注入，初始化期还是空串，这里只能用字面量。
        runCatching {
            if (WePrefs.containsKey(LEGACY_FEATURE_NAME)) {
                WePrefs.remove(LEGACY_FEATURE_NAME)
                WeLogger.i(TAG, "已清理旧开关「$LEGACY_FEATURE_NAME」（主题工坊默认关闭）")
            }
        }.onFailure { WeLogger.w(TAG, "清理旧主题开关失败：${it.message}") }
    }

    // ══════════════════════════════════════════════════════════
    // 持久化
    // ══════════════════════════════════════════════════════════
    private var activeThemeId by prefOption("theme_store_active_id", "")

    /**
     * 壁纸挂载连续失败计数。
     *
     * 往宿主 DecorView 插层 + 清背景属于「侵入式美化」，个别 ROM / 微信版本上
     * 可能引起宿主自己的布局或无障碍逻辑异常。连续失败就自动回退成微信原生，
     * 宁可没壁纸，也不让微信反复出问题。
     */
    /**
     * 连续挂载失败次数：**只在进程内计数，绝不持久化**。
     *
     * 持久化会让历史上的异常把主题永久锁死：3 次失败后 prefs 里就是 3，
     * 之后每次启动都直接 return，用户表现为「点哪个主题都没反应」。
     */
    @Volatile
    private var wallpaperFailStreak = 0

    /** 记录哪些 Activity 当前真的挂着背景层，避免对没挂过的页面白跑 removeWallpaper。 */
    private val wallpaperAttached = java.util.WeakHashMap<Activity, Boolean>()
    private var overlayOpacityPct by prefOption("theme_store_overlay_opacity", 40)

    /**
     * 壁纸渲染方式。
     *
     * 默认「覆盖」：微信 8.0.79 上主界面容器基本都是不透明绘制，
     * 垫在底层（旧 TabTheme 的做法）会被完全盖住 —— 用户看到的就是「点了应用没反应」。
     * 覆盖式是纯叠层，不依赖页面透明，任何版本都一定能看见；不透明度靠「壁纸透明度」控制。
     */
    // 默认用「覆盖」：微信 8.0.79 的主界面有多层不透明底板（会话列表外壳 + 每行 item），
    // 底层方案必须逐层清背景 + 定时重清才能透出来（同类模块 OKK 为此写了近千行清理逻辑）。
    // 覆盖式是纯叠层，装上就能看见，用户再用「壁纸透明度」调浓淡。
    private var renderModePref by prefOption("theme_store_render_mode_v3", RENDER_OVERLAY)

    private fun isOverlayMode(): Boolean = renderModePref == RENDER_OVERLAY

    /** 渲染方式的展示名。 */
    fun renderModeName(): String = if (isOverlayMode()) "覆盖（推荐）" else "背景（垫底层）"

    /** 切换渲染方式：立刻撤掉旧层并在新位置重挂。 */
    fun setRenderMode(overlay: Boolean) {
        renderModePref = if (overlay) RENDER_OVERLAY else RENDER_BACKGROUND
        runCatching {
            liveActivities().forEach { act ->
                detach(act.window?.decorView?.findViewWithTag<View>(TAB_ROOT_TAG))
                restoreClearedBackgrounds(act)
            }
        }
        tabBitmapPath = null
        lastLoggedWallpaper = null
        wallpaperFailStreak = 0
        applyThemeToAllLive()
        WeLogger.i(TAG, "渲染方式已切换：${renderModeName()}")
    }

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
        override fun onActivityPaused(activity: Activity) {
            // 退到后台先把自己那层藏起来：聊天页若是半透明窗口、或正处于转场那几百毫秒，
            // 底下主界面的壁纸就会透出来，跟微信自带聊天背景叠在一起。
            runCatching {
                if (!activity.javaClass.name.orEmpty().endsWith("LauncherUI")) return@runCatching
                activity.window?.decorView?.findViewWithTag<View>(TAB_ROOT_TAG)
                    ?.visibility = View.INVISIBLE
                // LauncherUI 退后台后重新回来可能是重建的，残留的页码会挂错图
                lastTabIndex = -1
            }.onFailure { WeLogger.d(TAG, "onActivityPaused 隐藏壁纸失败：${it.message}") }
        }
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            windowBackups.remove(System.identityHashCode(activity))
        }
    }

    override fun onEnable() {
        // 清掉历史遗留的持久化失败计数（老版本写进去的脏值会让主题被永久锁死）
        runCatching { WePrefs.remove("theme_store_wallpaper_fail") }
        hookTabSwitch()
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
    /**
     * 读模块 APK 里的内置素材（背景图 / 点九气泡）。
     *
     * 原来只有 createPackageContext 一条路，在微信进程里一旦被拒，
     * 所有内置主题就只剩一个纯色块 —— 用户感知就是「点了默认主题没效果」。
     * 这里补两条兜底：模块 ClassLoader 直接命中 APK 条目、直接开模块 APK 读 zip。
     */
    private fun readBuiltinAsset(assetPath: String): ByteArray? {
        runCatching {
            val pkgCtx = HostApplication?.createPackageContext(BuildConfig.APPLICATION_ID, 0)
            pkgCtx?.assets?.open(assetPath)?.use { return it.readBytes() }
        }.onFailure { WeLogger.w(TAG, "createPackageContext 读素材失败：$assetPath（${it.message}）") }

        runCatching {
            ThemeStore::class.java.classLoader?.getResourceAsStream("assets/$assetPath")
                ?.use { return it.readBytes() }
        }.onFailure { WeLogger.w(TAG, "ClassLoader 读素材失败：$assetPath（${it.message}）") }

        runCatching {
            val apk = moduleApkPath() ?: return@runCatching
            java.util.zip.ZipFile(apk).use { zip ->
                zip.getEntry("assets/$assetPath")?.let { entry ->
                    zip.getInputStream(entry).use { return it.readBytes() }
                }
            }
        }.onFailure { WeLogger.w(TAG, "ZipFile 读素材失败：$assetPath（${it.message}）") }

        WeLogger.w(TAG, "内置素材三种方式都读不到：$assetPath")
        return null
    }

    /** 模块自身 APK 路径（用于直接从 zip 读 assets）。 */
    private fun moduleApkPath(): String? = runCatching {
        HostApplication?.packageManager
            ?.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)
            ?.sourceDir
    }.getOrNull()

    /** 确保某个主题的素材已提取到模块数据目录（壁纸 + 两个气泡）。 */
    private fun ensureBuiltinExtracted(theme: ThemeInfo, force: Boolean = false) {
        val dir = builtinThemeDir(theme)
        val entries = listOf(
            theme.wallpaperAsset to builtinWallpaperFile(theme),
            theme.bubbleOtherAsset to builtinBubbleFile(theme, LEFT_BUBBLE_FILE),
            theme.bubbleSelfAsset to builtinBubbleFile(theme, RIGHT_BUBBLE_FILE)
        )
        for ((asset, dest) in entries) {
            // Path 没有 size()，要用 Files.size；0 字节也算「没提取成功」
            if (!force && dest.exists() && Files.size(dest) > 0L) continue
            val bytes = readBuiltinAsset(asset)
            if (bytes == null) {
                WeLogger.e(TAG, "内置素材提取失败：${theme.id} / $asset（主题将缺少这一项）")
                continue
            }
            try {
                dir.createDirsSafe()
                Files.write(dest, bytes)
                if (Files.size(dest) <= 0L) WeLogger.w(TAG, "素材写入为空：$asset")
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
    /**
     * 应用主题前确保相关 Feature 已启用。
     *
     * 之前「点了内置主题没效果」的根因就在这里：applyThemeById 只写了 activeThemeId，
     * 但「主题商城」这个 Feature 从未被开启过 → hook 没装 → 页面上根本没人去画壁纸。
     * 气泡同理依赖「自定义消息气泡」。
     */
    private fun ensureThemeFeaturesEnabled() {
        runCatching {
            if (!isEnabled) {
                WeLogger.i(TAG, "主题工坊未启用，随「应用主题」自动开启")
                // 必须同步写 WePrefs：Feature.startup() 是从 pref 读开关的，
                // 只改内存里的 isEnabled 的话，重启微信后开关又是关的 → 主题整片失效。
                WePrefs.putBool(NEW_FEATURE_NAME, true)
                isEnabled = true
            }
            com.Johnny.wcx.features.core.FeaturesProvider.ALL_HOOK_ITEMS
                .filterIsInstance<com.Johnny.wcx.features.core.SwitchFeature>()
                .firstOrNull { it.name == CUSTOM_BUBBLES_PREF_KEY }
                ?.takeIf { !it.isEnabled }
                ?.let {
                    WeLogger.i(TAG, "自定义消息气泡未启用，随「应用主题」自动开启")
                    WePrefs.putBool(CUSTOM_BUBBLES_PREF_KEY, true)
                    it.isEnabled = true
                }
        }.onFailure { WeLogger.w(TAG, "自动启用主题相关功能失败：${it.message}") }
    }

    private fun applyThemeInternal(id: String) {
        ensureThemeFeaturesEnabled()
        // 外部主题包优先：壁纸与左右气泡取自包内素材，缺哪项就跳过，不影响其它维度。
        ThemePack.find(id)?.let {
            applyPackInternal(it)
            return
        }
        val theme = builtinTheme(id) ?: run { WeLogger.w(TAG, "未知主题：$id"); return }
        try {
            ensureBuiltinExtracted(theme)
            backupExistingBubbles()
            // 气泡写入模块 assets（left=对方 / right=自己），并开启「自定义消息气泡」开关
            replaceBubblesAtomically(
                listOfNotNull(
                    builtinBubbleFile(theme, LEFT_BUBBLE_FILE)
                        .takeIf { it.exists() && it.toFile().length() > 0 }
                        ?.let { it to (KnownPaths.moduleAssets / LEFT_BUBBLE_FILE) },
                    builtinBubbleFile(theme, RIGHT_BUBBLE_FILE)
                        .takeIf { it.exists() && it.toFile().length() > 0 }
                        ?.let { it to (KnownPaths.moduleAssets / RIGHT_BUBBLE_FILE) },
                )
            )
            WePrefs.putBool(CUSTOM_BUBBLES_PREF_KEY, true)
            activeThemeId = id
            // 切到内置主题时清掉主题包游标，避免上一套主题包继续生效
            ThemePack.currentId = ""
            applyThemeToAllLive()
            WeLogger.i(TAG, "已应用主题：${theme.name}")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "applyThemeInternal 异常", e)
        }
    }

    /**
     * 把当前生效的主题（内置或主题包）打包成 zip，可直接分享给别人导入。
     * 内置主题会先按同样格式生成一个主题包再打包，保证两边格式一致。
     */
    fun exportActiveTheme(): File? {
        val id = activeThemeId
        if (id.isEmpty()) {
            WeLogger.w(TAG, "没有生效中的主题，无法导出")
            return null
        }
        ThemePack.find(id)?.let { return ThemePack.exportZip(it).getOrNull() }
        val theme = builtinTheme(id) ?: run {
            WeLogger.w(TAG, "未知主题：$id")
            return null
        }
        val pack = ThemePack.createPackFromFiles(
            name = theme.name,
            wallpaper = builtinWallpaperFile(theme).takeIf { it.exists() }?.toFile(),
            bubbleLeft = builtinBubbleFile(theme, LEFT_BUBBLE_FILE).takeIf { it.exists() }?.toFile(),
            bubbleRight = builtinBubbleFile(theme, RIGHT_BUBBLE_FILE).takeIf { it.exists() }?.toFile(),
        ).getOrNull() ?: return null
        return ThemePack.exportZip(pack).getOrNull()
    }

    /**
     * 原子替换气泡：先把新气泡都写成 .tmp，全部成功后再逐个 move 覆盖。
     *
     * - 任一步失败 → 删掉 tmp，旧气泡原样保留（不会出现「两边气泡都没了」的空窗）
     * - 新主题没提供的那一侧，才删掉旧的，避免新旧叠加
     */
    private fun replaceBubblesAtomically(entries: List<Pair<java.nio.file.Path, java.nio.file.Path>>) {
        val staged = ArrayList<Pair<java.nio.file.Path, java.nio.file.Path>>()
        try {
            for ((src, dst) in entries) {
                val tmp = dst.resolveSibling("${dst.fileName}.tmp")
                Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING)
                if (Files.size(tmp) <= 0L) error("气泡写入为空：$src")
                staged += tmp to dst
            }
            for ((tmp, dst) in staged) {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING)
            }
            // 新主题没给的那一侧：删掉旧的，避免与新气泡叠加
            val provided = entries.map { it.second.fileName.toString() }.toSet()
            listOf(LEFT_BUBBLE_FILE, RIGHT_BUBBLE_FILE).forEach { name ->
                if (name !in provided) {
                    runCatching { Files.deleteIfExists(KnownPaths.moduleAssets / name) }
                }
            }
            WeLogger.i(TAG, "气泡已替换：${provided.joinToString()}")
        } catch (e: Throwable) {
            staged.forEach { (tmp, _) -> runCatching { Files.deleteIfExists(tmp) } }
            WeLogger.w(TAG, "替换气泡失败，保留原有气泡：${e.message}")
            throw e
        }
    }

    /** 应用外部主题包（ThemePack）：壁纸 + 左右气泡。 */
    /** 清掉模块目录里当前生效的气泡文件（应用新主题前调用，避免新旧气泡叠加）。 */
    private fun clearCurrentBubbles() {
        runCatching {
            java.nio.file.Files.deleteIfExists(KnownPaths.moduleAssets / LEFT_BUBBLE_FILE)
            java.nio.file.Files.deleteIfExists(KnownPaths.moduleAssets / RIGHT_BUBBLE_FILE)
        }.onFailure { WeLogger.w(TAG, "清理旧气泡失败：${it.message}") }
    }

    private fun applyPackInternal(pack: ThemePack.Pack) {
        try {
            val left = pack.bubbleOther()
            val right = pack.bubbleSelf()
            if (left != null || right != null) {
                backupExistingBubbles()
                // 原子替换：失败保留旧气泡；新主题没给的那一侧才删掉旧文件
                replaceBubblesAtomically(
                    listOfNotNull(
                        left?.toPath()?.let { it to (KnownPaths.moduleAssets / LEFT_BUBBLE_FILE) },
                        right?.toPath()?.let { it to (KnownPaths.moduleAssets / RIGHT_BUBBLE_FILE) },
                    )
                )
                WePrefs.putBool(CUSTOM_BUBBLES_PREF_KEY, true)
            }
            activeThemeId = pack.id
            // 关键：壁纸读取走 ThemePack.active()（读 currentId）。
            // 只写 activeThemeId 不写 currentId，重启微信后 wallpaperSource 永远是 null，
            // 表现就是「应用了、也划掉微信重进了，但还是没背景」。
            ThemePack.currentId = pack.id
            applyThemeToAllLive()
            WeLogger.i(
                TAG,
                "已应用主题包：${pack.name}（壁纸=${pack.wallpaper() != null}，气泡=${left != null}/${right != null}）"
            )
        } catch (e: Throwable) {
            WeLogger.e(TAG, "applyPackInternal 异常", e)
        }
    }

    /** 壁纸文件（内置主题提取文件）。 */
    private fun wallpaperSource(activity: Activity? = null): java.nio.file.Path? {
        // 聊天会话页：只用微信自带聊天背景（用户可在微信里设置）；
        // 只有打开「随机聊天背景」时才由本模块接管，且只吃图池里的随机图，绝不吃主题壁纸。
        if (activity != null && isChattingPage(activity)) {
            if (!WePrefs.getBoolOrFalse(ThemeExtras.KEY_RANDOM_WALLPAPER)) return null
            val pool = ThemePack.randomPoolFiles()
            return pool.takeIf { it.isNotEmpty() }?.random()?.toPath()
        }
        // 扫一扫 / 看图页不挂任何壁纸
        if (activity != null && (isScannerPage(activity) || isPhotoViewerPage(activity))) return null
        // 主题壁纸只挂在微信主界面（主页/通讯录/发现/我 都在 LauncherUI 的 4 个 Tab 里）。
        // 设置页、聊天页、任何其它 Activity 都不挂，避免"主题乱铺"。
        if (activity != null && !activity.javaClass.name.orEmpty().endsWith("LauncherUI")) return null

        // 其余页面（主页 / 通讯录 / 发现 / 我 等）：走主题壁纸。
        // 这里不再使用随机图池 —— 随机图只属于聊天页。
        // 外部主题包优先，且支持分页面壁纸（某页单独设了就用它，没设退回全局）
        val key = activity?.let { pageWallpaperKey(it) }
        ThemePack.active()?.let { pack ->
            // 本页壁纸优先，没设就退回全局壁纸
            val candidates = buildList {
                if (key != null) pack.wallpaperFor(key)?.let(::add)
                pack.wallpaper()?.let(::add)
            }.distinctBy { it.absolutePath }
            candidates.firstOrNull()?.let { return it.toPath() }
        }
        // 内置主题：素材文件可能被清理掉（清缓存/换设备），这里顺手补一次提取，
        // 否则重启后只剩一个纯色块，用户会以为「应用了没生效」。
        val builtin = builtinTheme(activeThemeId) ?: return null
        runCatching { ensureBuiltinExtracted(builtin) }
            .onFailure { WeLogger.w(TAG, "补提取内置素材失败：${it.message}") }
        return builtinWallpaperFile(builtin).takeIf { it.exists() }
    }

    @Volatile
    private var lastTabIndex = -1

    /** 已挂过切页监听的 adapter，避免每次进主页重复 hook。 */
    private val hookedTabsAdapters: MutableSet<Any> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

    /**
     * 主界面切 Tab 时立即换壁纸。
     *
     * 分页面壁纸（消息/通讯录/发现/我）只有在这里重挂才能「切过去就变」，
     * 否则要退出重进主界面才生效。
     */
    private fun hookTabSwitch() {
        runCatching {
            WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
                val activity = thisObject as? Activity ?: return@hookAfter
                // 每次主界面创建/重建都重新挂一遍 4 页背景
                runCatching { applyTabWallpapers(activity) }
                // doOnCreate 时 DecorView 往往还没完成首次布局，挂上去的层可能被后加的不透明容器盖住。
                // 下一帧再挂一次（只补一次，不轮询），兜住「重启回来没效果」的大头。
                activity.window?.decorView?.post { runCatching { applyTabWallpapers(activity) } }
                val tabsAdapter = runCatching {
                    thisObject.reflekt().firstField { name = "mTabsAdapter" }.get()
                }.getOrNull() ?: return@hookAfter
                if (!hookedTabsAdapters.add(tabsAdapter)) return@hookAfter
                runCatching {
                    tabsAdapter.reflekt().firstMethod { name = "onPageScrolled" }.hookBefore {
                        val position = args.getOrNull(0) as? Int ?: return@hookBefore
                        val offset = args.getOrNull(1) as? Float ?: return@hookBefore
                        if (offset == 0f && position != lastTabIndex) {
                            lastTabIndex = position
                            // 切 Tab 只切显示哪张图，不重新解码
                            updateTabVisibility(activity, position)
                        }
                    }
                }.onFailure { WeLogger.w(TAG, "hook onPageScrolled 失败", it) }
            }
        }.onFailure { WeLogger.w(TAG, "hookTabSwitch 失败", it) }
    }

    /** 把 Activity 映射成分页面壁纸的 key：chats/contacts/discover/me/chatting。 */
    private fun pageWallpaperKey(activity: Activity): String? {
        val name = activity.javaClass.name ?: return null
        // 聊天页不做主题背景：微信自带聊天背景设置，重复接管只会互相打架。
        if (isChattingPage(activity)) return null
        if (name != "com.tencent.mm.ui.LauncherUI" && !name.endsWith("LauncherUI")) return null
        return when (currentTabIndex(activity)) {
            0 -> "chats"
            1 -> "contacts"
            2 -> "discover"
            3 -> "me"
            else -> null
        }
    }

    /** 主界面当前 Tab：直接读 ViewPager 的 mCurItem，避免依赖混淆后的 adapter 方法名。 */
    private fun currentTabIndex(activity: Activity): Int = runCatching {
        val content = activity.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
            ?: return@runCatching 0
        val pager = findViewByClass(content, "androidx.viewpager.widget.ViewPager")
            ?: return@runCatching 0
        pager.reflekt().firstField { name = "mCurItem" }.get() as? Int ?: 0
    }.getOrDefault(0)

    private fun findViewByClass(root: ViewGroup, className: String): View? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.javaClass.name == className) return child
            if (child is ViewGroup) findViewByClass(child, className)?.let { return it }
        }
        return null
    }

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
        // 扫一扫页面与照片/视频「放大查看」页面不挂壁纸：相机取景被遮挡影响扫码、
        // 查看页被壁纸盖住会与照片重叠。查看页多为半透明 Activity / 同窗口浮层 / 独立窗口，
        // 不一定有 Activity resume 信号——因此聊天页（ChattingUI）本身也不挂壁纸，
        // 从源头消除「照片透出聊天页壁纸蒙版」的可能；返回时其他页面 onResume 自动恢复。
        when {
            // 扫一扫 / 看图页：任何背景都不挂
            isScannerPage(activity) || isPhotoViewerPage(activity) -> removeWallpaper(activity)
            // 聊天页：只允许「随机聊天背景」图池，主题壁纸一律不进来。
            // 同时把 LauncherUI 上的背景层藏起来 —— 微信聊天页是独立窗口，
            // 但某些机型/版本下半透明转场时会透出后面的主界面背景，
            // 造成「聊天页里还能看到主页那四张背景」，与微信自带聊天背景叠在一起。
            isChattingPage(activity) -> {
                setTabWallpapersVisible(false)
                applyChattingWallpaper(activity)
            }
            // 微信主界面：4 个 Tab 的分页背景
            activity.javaClass.name.orEmpty().endsWith("LauncherUI") -> applyTabWallpapers(activity)
            // 其它页面（设置页等）：不挂。只有真的挂过才去移除，
            // 否则会白跑一次 removeWallpaper（历史上还因此触发过全局背景还原）
            else -> if (wallpaperAttached[activity] == true) removeWallpaper(activity)
        }
    }

    /**
     * 聊天页：不挂壁纸（微信自带聊天背景，重复接管会互相打架）。
     *
     * 注意：8.0.79 实测类名是 com.tencent.mm.ui.chatting.ChattingUI（多一层 chatting）。
     * 老代码写的是 com.tencent.mm.ui.ChattingUI（少一层），条件永远不成立，
     * 于是全局主题的壁纸一层层盖进了聊天会话界面 —— 就是「聊天页还显示着主题背景」的原因。
     */
    private fun isChattingPage(activity: Activity): Boolean {
        val name = activity.javaClass.name ?: return false
        // 前缀匹配 + 后缀兜底：微信历史上类名有 com.tencent.mm.ui.ChattingUI
        // 与 com.tencent.mm.ui.chatting.ChattingUI 两种写法，用 == 容易再踩一次。
        return name.startsWith("com.tencent.mm.ui.chatting.") || name.endsWith(".ChattingUI")
    }

    

    /** 微信「扫一扫」相关页面：plugin.scanner 包（扫码页/选模式/相册扫码/扫码结果等）。 */
    private fun isScannerPage(activity: Activity): Boolean =
        activity.javaClass.name?.startsWith("com.tencent.mm.plugin.scanner") == true

    /**
     * 照片/视频「放大查看」页面：壁纸若悬浮其上会盖住照片，一律不挂壁纸。
     * 类名取自微信 8.0.77 manifest 实测：
     * - ui.chatting.gallery.* ：聊天图片/视频查看（ImageGalleryUI 等）与聊天记录媒体页
     * - plugin.gallery.*      ：相册选图预览
     * - plugin.sns.ui.SnsGalleryUI / SnsSightPlayerUI / VideoFullScreenActivity：朋友圈照片/视频
     * - plugin.fav.ui.FavImgGalleryUI / gallery.FavMediaGalleryUI / detail.FavoriteSightDetailUI：收藏媒体
     * - view.activity.ImageQueryGalleryUI、ui.media.MediaGalleryContainerUI：通用媒体查看容器
     */
    private fun isPhotoViewerPage(activity: Activity): Boolean {
        val name = activity.javaClass.name ?: return false
        return name.startsWith("com.tencent.mm.ui.chatting.gallery") ||
            name.startsWith("com.tencent.mm.plugin.gallery") ||
            name == "com.tencent.mm.plugin.sns.ui.SnsGalleryUI" ||
            name == "com.tencent.mm.plugin.sns.ui.SnsSightPlayerUI" ||
            name == "com.tencent.mm.plugin.sns.ui.VideoFullScreenActivity" ||
            name == "com.tencent.mm.plugin.fav.ui.FavImgGalleryUI" ||
            name == "com.tencent.mm.plugin.fav.ui.gallery.FavMediaGalleryUI" ||
            name == "com.tencent.mm.plugin.fav.ui.detail.FavoriteSightDetailUI" ||
            name == "com.tencent.mm.view.activity.ImageQueryGalleryUI" ||
            name == "com.tencent.mm.ui.media.MediaGalleryContainerUI"
    }

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
     * 注：曾尝试「内容底衬」（挂在窗体容器 index 0）——微信各页根布局不透明会完全盖住底衬
     * （看不到壁纸），且影响微信自绘窗口（wcx 设置入口卡启动图），故回退为顶层悬浮。
     */
    // ══════════════════════════════════════════════════════════
    // 4 个 Tab 的分页背景（沿用原「四 Tab 主题背景」那套已验证可用的实现）
    //
    // 做法：在 DecorView 最底层放一个容器，里面每个 Tab 一个 ImageView，
    // 切 Tab 时只把对应当前 Tab 的那张设为可见。
    // 直接把图设成窗口背景是看不见的（会被微信页面自身底色盖住），
    // 而放在 DecorView index 0 且页面本身透明时就能透出来 —— 这也是旧版能生效的原因。
    // ══════════════════════════════════════════════════════════

    /**
     * 壁纸透明度上下限。
     *
     * 上限刻意压到 70%：就算背景层因为机型差异跑到内容之上，
     * 也保证文字、分割线、头像这些始终看得清。100% 只留给自己看的极客场景没有意义。
     */
    private const val MIN_OPACITY = 5
    private const val MAX_OPACITY = 70

    private const val TAB_ROOT_TAG = "wcx_theme_tab_root"

    private val TAB_NAMES = listOf("主页", "通讯录", "发现", "我")

    /** 日志去重：同一组状态只打一次。 */
    @Volatile
    private var lastLoggedWallpaper: String? = null


    /** 4 个页面各自的背景文件；内置主题没有分页面素材时用同一张全局图。 */
    private fun tabWallpaperFiles(): List<File?> {
        val pack = ThemePack.active()
        // 统一用 java.io.File：ThemePack 返回 File，内置素材是 Path，混用会推断成 Comparable
        val builtin: File? = builtinTheme(activeThemeId)?.let { theme ->
            runCatching { ensureBuiltinExtracted(theme) }
            builtinWallpaperFile(theme).takeIf { it.exists() }?.toFile()
        }
        return listOf("chats", "contacts", "discover", "me").map { key ->
            pack?.wallpaperFor(key) ?: builtin
        }
    }

    /** 当前 tab 缓存，避免每次切页都重新 decode。 */
    @Volatile
    private var tabBitmapPath: String? = null

    /** tabIndex 对应的图片文件；该页没设就退回第一张有图的页。 */
    private fun fileForTab(files: List<File?>, tab: Int): File? =
        files.getOrNull(tab) ?: files.firstOrNull { it != null }

    private fun describeDrawable(d: android.graphics.drawable.Drawable?): String = when (d) {
        null -> "null"
        is android.graphics.drawable.BitmapDrawable ->
            "BitmapDrawable(${d.bitmap?.width}x${d.bitmap?.height}, alpha=${d.alpha})"
        is android.graphics.drawable.ColorDrawable ->
            "ColorDrawable(#${Integer.toHexString(d.color)})"
        else -> d.javaClass.simpleName
    }

    /** 记录各 DecorView 原来自己的 background，撤销主题时原样放回。 */
    private val savedDecorBackground = java.util.WeakHashMap<ViewGroup, android.graphics.drawable.Drawable?>()

    /**
     * 用 DecorView 的 background 承载壁纸。
     *
     * 与旧「四 Tab 主题背景」在 DecorView index 0 插一层是同层级效果（都画在所有子 View 之下），
     * 但不增加任何子 View —— 微信按子节点顺序找窗口容器的代码不受影响。
     */
    private fun attachAsDecorBackground(activity: Activity, decor: ViewGroup, file: File?) {
        if (!savedDecorBackground.containsKey(decor)) savedDecorBackground[decor] = decor.background
        val bmp = file?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
        if (bmp == null) {
            WeLogger.w(TAG, "背景文件解码失败：${file?.absolutePath ?: "null"}")
            return
        }
        val drawable = android.graphics.drawable.BitmapDrawable(activity.resources, bmp)
        drawable.setGravity(android.view.Gravity.FILL)
        drawable.alpha = (overlayOpacityPct.coerceIn(MIN_OPACITY, MAX_OPACITY) * 255 / 100).coerceIn(0, 255)
        decor.background = drawable
    }

    /** 还原 DecorView 原来的 background。 */
    private fun restoreDecorBackground(decor: ViewGroup) {
        val original = savedDecorBackground.remove(decor) ?: return
        runCatching { decor.background = original }
    }

    /** 从它当前的父容器摘掉自己（壁纸层可能挂在内容容器的父级，不一定是 DecorView）。 */
    private fun detach(v: View?) {
        val parent = v?.parent as? ViewGroup ?: return
        runCatching { parent.removeView(v) }
    }

    /**
     * 挂 / 更新 4 个页面的背景。
     *
     * 只用**一个自绘 View**（没有子 View），插在 android.R.id.content 的最底层：
     * 子 View 越少、类型越简单，微信无障碍的视图遍历越不容易出问题
     * （之前用 FrameLayout + 4 个 ImageView，多出了 5 个陌生节点）。
     */
    private fun applyTabWallpapers(activity: Activity) {
        // 只暂停本进程内的重试；不改 activeThemeId，也不写 pref
        if (wallpaperFailStreak >= 3) return
        val decor = activity.window?.decorView as? ViewGroup
        if (decor == null) {
            WeLogger.w(TAG, "applyTabWallpapers 跳过：decorView 不可用（${activity.javaClass.name}）")
            return
        }
        val files = tabWallpaperFiles()
        val existing = decor.findViewWithTag<View>(TAB_ROOT_TAG)
        if (files.all { it == null }) {
            WeLogger.w(TAG, "applyTabWallpapers 跳过：当前主题没有可用壁纸文件（active=$activeThemeId）")
            detach(existing)
            tabBitmapPath = null
            return
        }
        // 不能插在 DecorView 的 index 0：微信 LauncherUI 空闲时会预加载聊天页
        // （Log tag MicroMsg.LauncherUI.NewChattingTabUI / 方法 queueIdle），那里按 DecorView
        // 既有子节点找窗口内容容器。多插一层会被它当成容器，findViewById 返回 null 后
        // setVisibility 直接 NPE 崩主线程（2026-10-27 02:24 那次崩溃）。
        // 覆盖模式挂在 DecorView 顶层（追加到末尾）：不占 index 0，微信 getChildAt(0) 不受影响；
        // 覆盖整屏（含系统栏），也不受 content 里 Fragment 布局/尺寸的影响（早前挂在 content 里
        // 出现过 size=0x0 —— 后台 Activity 不 layout，那一层永远画不出来）。
        val host = decor
        val slot = decor.childCount
        if (!isOverlayMode()) {
            // 旧「四 Tab 主题背景」的等效实现：不插任何 View，直接写 DecorView 的 background。
            try {
                detach(existing)
                // 以当前真实页码为准
                val tab = currentTabIndex(activity).takeIf { it in 0..3 } ?: lastTabIndex
                if (tab in 0..3) lastTabIndex = tab
                val file = fileForTab(files, tab)
                attachAsDecorBackground(activity, decor, file)
                // 光设 DecorView 背景不够：微信主界面的页面底板（content 里的根容器、Fragment 容器）
                // 常常自带不透明底色，会把这一层彻底盖住 —— 这就是「设置了但看不见」的原因。
                // 只清「装饰层 / 内容容器」这两层，不动列表、气泡、文字本身。
                clearSurfaceLite(activity, decor)
                // 微信主界面是多层不透明容器叠起来的（会话列表外壳 + 每个 item 行），
                // 只清两层透不出来。这里把所有容器类 View 的背景一起清掉，
                // 再挂几个延迟重清（微信会把它设回去）。
                val clearedCount = runCatching { clearAllContainerBackgrounds(activity, decor) }.getOrDefault(0)
                if (clearedCount > 0) WeLogger.i(TAG, "已清容器背景 $clearedCount 层")
                scheduleReclear(activity)
                wallpaperAttached[activity] = true
                wallpaperFailStreak = 0
                val info = files.mapIndexed { i, f -> "${TAB_NAMES[i]}=${f != null}" }.joinToString("，")
                val logKey = "bg|$info|tab=$tab|${file?.absolutePath}"
                if (logKey != lastLoggedWallpaper) {
                    lastLoggedWallpaper = logKey
                    WeLogger.i(TAG, "4 页背景已挂载（DecorView background，$info），Tab=$tab，文件=${file?.absolutePath ?: "null"}")
                }
            } catch (e: Throwable) {
                wallpaperFailStreak += 1
                WeLogger.w(TAG, "写 DecorView 背景失败（第 $wallpaperFailStreak 次）：${e.message}", e)
            }
            return
        }
        try {
            val view: ThemeWallpaperView = if (existing is ThemeWallpaperView) {
                existing
            } else {
                detach(existing)
                ThemeWallpaperView(activity).also {
                    it.tag = TAB_ROOT_TAG
                    host.addView(
                        it,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            }
            if (view.parent !== host) {
                val lp = view.layoutParams
                    ?: ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                detach(view)
                host.addView(view, lp)
            } else {
                host.bringChildToFront(view)
            }
            view.visibility = View.VISIBLE
            // 以当前真实页码为准：LauncherUI 重建后 lastTabIndex 是上一次的残值，会挂错图
            val tab = currentTabIndex(activity).takeIf { it in 0..3 } ?: lastTabIndex
            if (tab in 0..3) lastTabIndex = tab
            // 页码取不到时兜底用第一张可用图，宁可显示「主页」那张，也不要一片空白
            val file = fileForTab(files, tab) ?: files.firstOrNull { it != null }
            // 复合 key：路径 + 修改时间。bitmap 为空时必须重解（上一次可能因为后台/未 attach 失败）
            val path = file?.let { "${it.absolutePath}|${it.lastModified()}" }
            if (path != null && (path != tabBitmapPath || view.bitmap == null)) {
                val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                if (bmp != null) {
                    view.bitmap = bmp
                    tabBitmapPath = path
                } else {
                    WeLogger.w(TAG, "壁纸解码失败：$path")
                }
            }
            view.requestLayout()
            view.alpha = overlayOpacityPct.coerceIn(MIN_OPACITY, MAX_OPACITY) / 100f
            wallpaperAttached[activity] = true
            wallpaperFailStreak = 0
            val info = files.mapIndexed { i, f -> "${TAB_NAMES[i]}=${f != null}" }.joinToString("，")
            val logKey = "$info|tab=$tab|$path"
            if (logKey != lastLoggedWallpaper) {
                lastLoggedWallpaper = logKey
                WeLogger.i(TAG, "4 页背景已挂载（$info），当前 Tab=$tab，文件=${file?.absolutePath ?: "null"}")
            }
        } catch (e: Throwable) {
            wallpaperFailStreak += 1
            WeLogger.w(TAG, "applyTabWallpapers 第 ${wallpaperFailStreak} 次连续失败：${e.message}", e)
            if (wallpaperFailStreak >= 3) {
                // 只停止本进程内的重试，不清 activeThemeId —— 否则会变成「点哪个主题都没反应」
                WeLogger.w(TAG, "背景连续失败 3 次，本进程内暂停重挂；点「应用」会重新开始")
            }
        }
    }

    // 被清掉的原背景：按 Activity 隔离，撤销时只还原自己那份
    private val clearedBackgrounds =
        java.util.WeakHashMap<Activity, java.util.WeakHashMap<View, android.graphics.drawable.Drawable?>>()

    /**
     * 只清「挡在壁纸前面、且属于页面底板」的那一层，跳过 Tab 栏 / 标题栏等结构条。
     * 参数带 activity：清理记录按页面隔离，避免 A 页面还原把 B 页面刚清的底色还回去。
     */
    private fun clearSurfaceLite(activity: Activity, decor: ViewGroup) {
        runCatching {
            for (i in 0 until decor.childCount) {
                val child = decor.getChildAt(i) ?: continue
                if (child.tag == TAB_ROOT_TAG) continue
                if (isStructuralBar(child)) continue
                clearBg(activity, child)
                if (child !is ViewGroup) continue
                for (j in 0 until child.childCount) {
                    val inner = child.getChildAt(j) ?: continue
                    if (inner.tag == TAB_ROOT_TAG) continue
                    if (isStructuralBar(inner)) continue
                    if (inner.id == android.R.id.content && inner is ViewGroup) {
                        for (k in 0 until inner.childCount) {
                            val leaf = inner.getChildAt(k) ?: continue
                            if (isStructuralBar(leaf)) continue
                            clearBg(activity, leaf)
                        }
                    } else {
                        clearBg(activity, inner)
                    }
                }
            }
        }.onFailure { WeLogger.w(TAG, "清理容器背景异常：${it.message}") }
    }

    /**
     * 暴力清「容器类」背景：递归清掉所有 ViewGroup 的 background。
     *
     * 不动 TextView / ImageView（文字和图标自己的底不能清），跳过结构条与我们自己的层。
     * 微信主界面有多层不透明容器，清两层根本透不出来 —— 同类模块 OKK 为此写了近千行清理逻辑，
     * 这里用「全部容器 + 定时重清」把它压到一个函数里。
     */
    private fun clearAllContainerBackgrounds(activity: Activity, root: ViewGroup): Int {
        var count = 0
        val limit = 80
        fun walk(v: View, depth: Int) {
            if (depth > 8 || count >= limit) return
            if (v.tag == TAB_ROOT_TAG) return
            if (v is TextView || v is ImageView) return
            if (isStructuralBar(v)) return
            if (v.background != null) {
                clearBg(activity, v)
                count++
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) v.getChildAt(i)?.let { child -> walk(child, depth + 1) }
            }
        }
        walk(root, 0)
        return count
    }

    /** 微信会把自己设的背景装回去，隔几拍再清一次。 */
    private fun scheduleReclear(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        listOf(300L, 1200L, 3000L).forEach { delay ->
            decor.postDelayed({
                if (!isOverlayMode() && wallpaperAttached[activity] == true) {
                    runCatching {
                        val n = clearAllContainerBackgrounds(activity, decor)
                        if (n > 0) WeLogger.i(TAG, "重清底板背景 $n 层")
                    }
                }
            }, delay)
        }
    }

    /** Tab 栏 / 标题栏 / 底部导航这类结构条不动，否则会出现壁纸从 Tab 栏透出来。 */
    private fun isStructuralBar(v: View): Boolean {
        if (v.id == android.R.id.tabs || v.id == android.R.id.content) return false
        val name = v.javaClass.name ?: return false
        if (name.contains("TabBar") || name.contains("ActionBar") ||
            name.contains("Toolbar") || name.contains("TitleBar") ||
            name.contains("NavigationBar") || name.contains("BottomBar")
        ) {
            return true
        }
        return name.contains("MMTab") || name.contains("ViewPagerIndicator")
    }

    private fun clearBg(activity: Activity, v: View) {
        val bg = v.background ?: return
        val map = clearedBackgrounds.getOrPut(activity) { java.util.WeakHashMap() }
        if (!map.containsKey(v)) map[v] = bg
        v.background = null
    }

    /** 只还原这一个 Activity 被清掉的底色。 */
    private fun restoreClearedBackgrounds(activity: Activity) {
        val map = clearedBackgrounds.remove(activity) ?: return
        map.forEach { (v, bg) -> runCatching { v.background = bg } }
    }

    /** 切 Tab：换一张图。 */
    private fun updateTabVisibility(activity: Activity, position: Int) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (!isOverlayMode()) {
            // background 模式：切页直接换 DecorView 的背景图，不动任何子 View
            runCatching {
                if (wallpaperAttached[activity] != true) return@runCatching
                val file = fileForTab(tabWallpaperFiles(), position)
                attachAsDecorBackground(activity, decor, file)
            }.onFailure { WeLogger.w(TAG, "切 Tab 换背景（background）失败：${it.message}") }
            return
        }
        val view = decor.findViewWithTag<View>(TAB_ROOT_TAG) as? ThemeWallpaperView ?: return
        runCatching {
            // 覆盖模式下微信可能把新页面加到我们上面，切 Tab 时重新提到最上层
            if (isOverlayMode()) (view.parent as? ViewGroup)?.bringChildToFront(view)
            val file = fileForTab(tabWallpaperFiles(), position)
            val path = file?.let { "${it.absolutePath}|${it.lastModified()}" }
            if (path != null && path != tabBitmapPath) {
                val bmp = BitmapFactory.decodeFile(path)
                if (bmp != null) {
                    view.bitmap = bmp
                    tabBitmapPath = path
                }
            }
        }.onFailure { WeLogger.w(TAG, "切 Tab 换背景失败：${it.message}") }
    }

    /** 统一显示/隐藏所有主界面的背景层（进聊天页时隐藏，回来再显示）。 */
    private fun setTabWallpapersVisible(visible: Boolean) {
        runCatching {
            liveActivities().forEach { act ->
                if (!act.javaClass.name.orEmpty().endsWith("LauncherUI")) return@forEach
                val layer = act.window?.decorView
                    ?.findViewWithTag<View>(TAB_ROOT_TAG) ?: return@forEach
                layer.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            }
        }.onFailure { WeLogger.w(TAG, "切换背景层可见性失败：${it.message}") }
    }

    /** 移除 4 页背景容器。 */
    private fun removeTabWallpapers(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        runCatching {
            detach(decor.findViewWithTag<View>(TAB_ROOT_TAG))
            restoreDecorBackground(decor)
            tabBitmapPath = null
            restoreClearedBackgrounds(activity)
        }
    }

    // ══════════════════════════════════════════════════════════
    // 聊天页随机背景（只吃图池，绝不吃主题壁纸）
    // ══════════════════════════════════════════════════════════

    private fun applyChattingWallpaper(activity: Activity) {
        val src = wallpaperSource(activity)
        if (src == null) {
            restoreWindowBackground(activity)
            return
        }
        runCatching {
            val bmp = BitmapFactory.decodeFile(src.absolutePathString())
            if (bmp == null) {
                WeLogger.e(TAG, "聊天页壁纸解码失败：${src.absolutePathString()}")
                restoreWindowBackground(activity)
                return
            }
            val pct = overlayOpacityPct.coerceIn(MIN_OPACITY, MAX_OPACITY)
            val drawable = android.graphics.drawable.BitmapDrawable(activity.resources, bmp).apply {
                setAlpha(pct * 255 / 100)
                gravity = android.view.Gravity.FILL
            }
            val window = activity.window ?: return
            if (!savedWindowBackgrounds.containsKey(activity)) {
                savedWindowBackgrounds[activity] = window.decorView?.background
            }
            window.setBackgroundDrawable(drawable)
            WeLogger.i(TAG, "聊天页随机背景已挂载：$src")
        }.onFailure { WeLogger.w(TAG, "聊天页随机背景失败：${it.message}") }
    }

    private fun restoreWindowBackground(activity: Activity) {
        if (!savedWindowBackgrounds.containsKey(activity)) return
        runCatching {
            activity.window?.setBackgroundDrawable(savedWindowBackgrounds.remove(activity))
        }
    }

    // 每个 Activity 原始的窗口背景，撤销主题时原样放回去
    private val savedWindowBackgrounds =
        java.util.WeakHashMap<Activity, android.graphics.drawable.Drawable?>()

    // 被清掉的原背景，撤销主题时原样放回去
    private fun removeWallpaper(activity: Activity) {
        removeTabWallpapers(activity)
        runCatching {
            val decor = activity.window?.decorView as? ViewGroup ?: return@runCatching
            // 兼容历史版本留下的一次性壁纸层
            detach(decor.findViewWithTag<View>(WALLPAPER_TAG))
        }
        restoreWindowBackground(activity)
        wallpaperAttached.remove(activity)
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
            ThemePack.currentId = ""
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

    /**
     * 透明度滑杆即时生效：对活着的页面重挂一次背景，让新的 alpha 立刻生效。
     */
    private fun updateAlphaOnLive(@Suppress("UNUSED_PARAMETER") alpha: Float) {
        liveActivities().forEach { act ->
            runCatching { applyThemeTo(act) }
                .onFailure { WeLogger.d(TAG, "更新透明度失败：${it.message}") }
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
        // 主题相关操作已全部并入「主题工坊」子页面，这里只做导航。
        SettingsNavigation.requestThemeWorkshop()
    }

    // ── 供「主题工坊」页面调用的公开入口 ─────────────────────────────────
    /** 内置主题（id to 名称）。 */
    fun builtinThemeItems(): List<Pair<String, String>> = builtinThemes.map { it.id to it.name }

    /** 内置主题的自定义名称（长按重命名后存这里，为空则用内置原名）。 */
    fun builtinAlias(id: String): String = WePrefs.getStringOrDef("theme_alias_$id", "")

    fun setBuiltinAlias(id: String, name: String) {
        WePrefs.putString("theme_alias_$id", name.trim())
    }

    /** 内置主题的自定义描述（可选）。 */
    fun builtinDesc(id: String): String = WePrefs.getStringOrDef("theme_desc_$id", "")

    fun setBuiltinDesc(id: String, desc: String) {
        WePrefs.putString("theme_desc_$id", desc.trim())
    }

    /** 内置主题展示名 = 自定义别名优先。 */
    fun builtinDisplayName(id: String, fallback: String): String =
        builtinAlias(id).ifBlank { fallback }

    /** 默认（内置）主题的缩略图文件；首次调用会把素材从 APK 提取到模块数据目录。 */
    fun builtinThumbnail(id: String): File? = runCatching {
        val theme = builtinTheme(id) ?: return@runCatching null
        ensureBuiltinExtracted(theme)
        builtinWallpaperFile(theme).takeIf { it.exists() }?.toFile()
    }.getOrNull()

    /** 默认主题的主题色，壁纸缺失时用来兜底显示。 */
    fun builtinColor(id: String): Long = builtinTheme(id)?.previewColor ?: 0xFFF0F0F0

    fun activeThemeIdOrEmpty(): String = activeThemeId

    /**
     * 主题全链路状态，供「主题诊断」一键复制。
     *
     * 逐层说明壁纸到底卡在哪：开关 → 当前主题 → 4 页素材文件 → 挂载位置/尺寸/是否有图 → 清了几个背景。
     * 之前「不生效」只能靠猜，这里全部给实数。
     */
    fun diagnose(): String = buildString {
        appendLine("== 主题工坊诊断（${BuildConfig.VERSION_NAME}）==")
        appendLine("功能开关 = " + runCatching { WePrefs.getBoolOrFalse(NEW_FEATURE_NAME) }.getOrDefault(false) + "（key=$NEW_FEATURE_NAME）")
        appendLine("activeThemeId = '$activeThemeId'")
        appendLine("ThemePack.currentId = '" + runCatching { ThemePack.currentId }.getOrDefault("异常") + "'")
        appendLine("ThemePack.active() = " + runCatching {
            ThemePack.active()?.let { pack -> "${pack.id} / ${pack.name}" } ?: "无"
        }.getOrDefault("异常"))
        appendLine("渲染方式 = ${renderModeName()}")
        appendLine("壁纸透明度 = ${overlayOpacity()}%")
        appendLine("自定义气泡开关 = " + runCatching { WePrefs.getBoolOrFalse(CUSTOM_BUBBLES_PREF_KEY) }.getOrDefault(false))
        appendLine("气泡文件存在 = left:" + (KnownPaths.moduleAssets / LEFT_BUBBLE_FILE).toFile().exists() +
            " right:" + (KnownPaths.moduleAssets / RIGHT_BUBBLE_FILE).toFile().exists())
        val files = runCatching { tabWallpaperFiles() }.getOrDefault(emptyList())
        appendLine("4 页素材：")
        files.forEachIndexed { index, file ->
            appendLine(
                "  ${TAB_NAMES.getOrElse(index) { index.toString() }} = " + if (file == null) {
                    "缺失"
                } else {
                    "${file.absolutePath} (${runCatching { file.length() }.getOrDefault(0L)} B)"
                }
            )
        }
        val acts = runCatching { liveActivities() }.getOrDefault(emptyList())
        appendLine("存活 Activity = " + acts.joinToString("、") { it.javaClass.simpleName })
        acts.forEach { act ->
            val decor = act.window?.decorView as? ViewGroup ?: return@forEach
            val content = runCatching { decor.findViewById<ViewGroup>(android.R.id.content) }.getOrNull()
            val layer = decor.findViewWithTag<View>(TAB_ROOT_TAG)
            appendLine("· ${act.javaClass.simpleName}")
            appendLine("  decor=${decor.javaClass.simpleName} childCount=${decor.childCount}")
            appendLine("  content=" + (content?.let { "${it.javaClass.simpleName} childCount=${it.childCount}" } ?: "未找到"))
            if (layer == null) {
                appendLine("  壁纸层=未挂载")
            } else {
                val parent = layer.parent as? ViewGroup
                appendLine(
                    "  壁纸层=已挂 parent=${parent?.javaClass?.simpleName}" +
                        " index=${parent?.indexOfChild(layer) ?: -1}" +
                        " 在content内=${parent === content}" +
                        " size=${layer.width}x${layer.height}" +
                        " vis=${layer.visibility}" +
                        " alpha=${layer.alpha}" +
                        " 有图=${(layer as? ThemeWallpaperView)?.bitmap != null}"
                )
            }
            appendLine("  decor.background=" + describeDrawable(decor.background))
            content?.let { c ->
                val items = buildList {
                    for (i in 0 until c.childCount) {
                        val child = c.getChildAt(i) ?: continue
                        add("${child.javaClass.simpleName}[bg=" + (child.background?.javaClass?.simpleName ?: "无") + "]")
                    }
                }
                appendLine("  content 子级=" + items.joinToString("、"))
            }
            appendLine("  已清背景层数=" + (clearedBackgrounds[act]?.size ?: 0))
            appendLine("  wallpaperAttached=" + (wallpaperAttached[act] == true))
        }
        appendLine("wallpaperFailStreak=$wallpaperFailStreak")
    }

    /** 应用主题（内置或主题包），并同步一次壁纸透明度。 */
    fun applyThemeById(id: String) {
        // 用户主动应用 = 重新给一次机会，清掉本进程内的连续失败计数
        wallpaperFailStreak = 0
        applyThemeInternal(id)
        updateAlphaOnLive(overlayOpacityPct.coerceIn(MIN_OPACITY, MAX_OPACITY) / 100f)
    }

    fun restoreDefaultTheme() = restoreDefault()

    /**
     * 清除主题相关缓存（应急用）。
     *
     * 场景：换主题后旧气泡没被替换、或没在用主题却还留着残留气泡时，
     * 一键把「模块目录里的气泡文件 + 内置素材提取缓存 + 已挂的背景层」全部清掉，
     * 让微信回到原生样式。不会删用户的主题包本身，也不会动气泡备份。
     */
    fun clearThemeCache() {
        runCatching {
            clearCurrentBubbles()
            // 内置素材提取缓存：下次应用主题会重新从 APK 提取
            runCatching {
                builtinDir.toFile().deleteRecursively()
                builtinDir.createDirsSafe()
            }
            tabBitmapPath = null
            lastLoggedWallpaper = null
            // 撤掉已经挂在页面上的背景
            liveActivities().forEach { removeWallpaper(it) }
            // 关掉气泡接管，避免它继续生效
            WePrefs.putBool(CUSTOM_BUBBLES_PREF_KEY, false)
            WeLogger.i(TAG, "已清除主题相关缓存（气泡 / 内置素材 / 背景层）")
        }.onFailure { WeLogger.w(TAG, "清除主题缓存失败：${it.message}") }
    }

    /**
     * 关掉「随机聊天背景」时，立刻把已经挂在聊天页上的壁纸撤掉，
     * 不用等用户退出再进一次聊天界面。
     */
    fun clearWallpaperOnChattingPages() {
        runCatching {
            liveActivities().filter { isChattingPage(it) }.forEach { removeWallpaper(it) }
        }.onFailure { WeLogger.w(TAG, "清除聊天页壁纸失败：${it.message}") }
    }

    /** 壁纸透明度（5~100）。 */
    fun overlayOpacity(): Int = overlayOpacityPct.coerceIn(MIN_OPACITY, MAX_OPACITY)

    fun setOverlayOpacity(pct: Int) {
        overlayOpacityPct = pct.coerceIn(MIN_OPACITY, MAX_OPACITY)
        updateAlphaOnLive(overlayOpacityPct / 100f)
    }

    // 旧「主题商城」对话框已下线：壁纸/气泡/透明度/导入导出全部并入「主题工坊」页面。

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

/**
 * 主题背景层：单个自绘 View，不持有任何子 View。
 *
 * 之所以不用 FrameLayout + ImageView 的组合，是因为往微信 DecorView 里塞的节点越少、
 * 类型越普通，宿主无障碍遍历（MMBaseAccessibilityConfig.mockInflateCore）就越不容易被扰动。
 */
private class ThemeWallpaperView(context: android.content.Context) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        if (bmp.width <= 0 || bmp.height <= 0 || width <= 0 || height <= 0) return
        // CENTER_CROP：等比放大铺满
        val scale = maxOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dx = (width - bmp.width * scale) / 2f
        val dy = (height - bmp.height * scale) / 2f
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        canvas.restore()
    }
}
