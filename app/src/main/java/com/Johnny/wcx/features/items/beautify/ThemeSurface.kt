package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.app.Application
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.graphics.Color as AndroidColor
import com.Johnny.wcx.features.api.ui.WeMainActivityBeautifyApi
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import java.lang.ref.WeakReference
import kotlin.io.path.absolutePathString
import java.util.concurrent.ConcurrentHashMap

/**
 * 页面分类：回答「这是哪个微信页面」，不涉及任何挂载动作。
 * 2026-10-27 从 ThemeStore 单体拆出。
 */
internal object ThemePages {

    private const val TAG = "ThemePages"

    /** 微信主界面（主页/通讯录/发现/我 4 个 Tab 都在 LauncherUI 里）。 */
    fun isLauncher(activity: Activity): Boolean =
        activity.javaClass.name.orEmpty().endsWith("LauncherUI")

    /**
     * 聊天会话页。
     *
     * 8.0.79 实测类名是 com.tencent.mm.ui.chatting.ChattingUI（多一层 chatting）。
     * 老代码写的是 com.tencent.mm.ui.ChattingUI（少一层），条件永远不成立，
     * 于是全局主题壁纸一层层盖进了聊天会话界面 —— 用前缀 + 后缀兜底，别再踩一次。
     */
    fun isChatting(activity: Activity): Boolean {
        val name = activity.javaClass.name ?: return false
        return name.startsWith("com.tencent.mm.ui.chatting.") || name.endsWith(".ChattingUI")
    }

    /** 微信「扫一扫」相关页面（plugin.scanner 包）。 */
    fun isScanner(activity: Activity): Boolean =
        activity.javaClass.name?.startsWith("com.tencent.mm.plugin.scanner") == true

    /**
     * 照片/视频「放大查看」页面：壁纸悬浮其上会盖住照片，一律不挂。
     * 类名取自微信 8.0.77 manifest 实测。
     */
    fun isPhotoViewer(activity: Activity): Boolean {
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

    /** Activity → 分页壁纸 key（chats / contacts / discover / me）。 */
    fun pageWallpaperKey(activity: Activity): String? {
        if (isChatting(activity)) return null
        if (!isLauncher(activity)) return null
        return when (ThemeSurface.currentTabIndex(activity)) {
            0 -> "chats"
            1 -> "contacts"
            2 -> "discover"
            3 -> "me"
            else -> null
        }
    }
}

/**
 * 主题的「挂载层」：把壁纸 / 沉浸式写到微信各个页面上，并跟随生命周期反复维持。
 *
 * 2026-10-27 从 ThemeStore 单体拆出，职责边界：
 * - 不读主题清单、不管素材提取（那是 [ThemeMaterials]）
 * - 不碰气泡（那是 [ThemeBubbleKit]）
 * - 不定义主题（那是 [BuiltinThemes] / [ThemePaths]）
 * - 只负责「哪个页面该怎么挂、挂不上怎么兜底、离开怎么还原」
 *
 * 壁纸渲染本身由 [TabWallpaperRenderer]（DecorView 前景遮罩）完成。
 */
internal object ThemeSurface {

    private const val TAG = "ThemeSurface"

    /** 壁纸透明度上下限：上限压到 70%，保证文字、分割线、头像始终看得清。 */
    const val MIN_OPACITY = 5
    const val MAX_OPACITY = 70

    /** 主界面当前 Tab；LauncherUI 退后台后清为 -1，回来重新读真实页码。 */
    @Volatile
    var lastTabIndex: Int = -1
        private set

    fun resetTabIndex() {
        lastTabIndex = -1
    }

    /**
     * 连续挂载失败次数：**只在进程内计数，绝不持久化**。
     *
     * 持久化会让历史异常把主题永久锁死：3 次失败后每次启动都直接 return，
     * 用户表现为「点哪个主题都没反应」。
     */
    @Volatile
    var wallpaperFailStreak: Int = 0
        private set

    fun resetFailStreak() {
        wallpaperFailStreak = 0
    }

    /** 记录哪些 Activity 真的挂过，避免对没挂过的页面白跑 removeWallpaper。 */
    private val wallpaperAttached = java.util.WeakHashMap<Activity, Boolean>()

    fun isAttached(activity: Activity): Boolean = wallpaperAttached[activity] == true

    /** 已挂过切页监听的 adapter，避免每次进主页重复 hook。 */
    private val hookedTabsAdapters: MutableSet<Any> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

    /** 通过 ActivityThread.mActivities 获取进程内所有存活 Activity。 */
    fun liveActivities(): List<Activity> = try {
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
                add(activity)
            }
        }
    } catch (e: Throwable) {
        WeLogger.w(TAG, "枚举存活 Activity 失败：${e.message}")
        emptyList()
    }

    // ══════════════════════════════════════════════════════════
    // 生命周期回调
    // ══════════════════════════════════════════════════════════

    @Volatile
    private var callbacksRegistered = false

    private val hostApp: Application? by lazy {
        runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? Application
        }.getOrNull()
    }

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}

        override fun onActivityResumed(activity: Activity) {
            // 主题没生效（没选主题）时一个字节都不动，避免「关了主题还在改窗口」
            if (ThemeStore.activeThemeIdOrEmpty().isEmpty()) return
            // 非主界面进前台：先把主界面遮罩藏掉（聊天页半透明时会透出后面这张壁纸）
            if (!ThemePages.isLauncher(activity)) hideLauncherWallpapers()
            runCatching { applyTo(activity) }
                .onFailure { WeLogger.w(TAG, "onActivityResumed 应用主题异常", it) }
        }

        override fun onActivityPaused(activity: Activity) {
            // 退到后台先把自己那层藏起来：聊天页若是半透明窗口、或正处于转场那几百毫秒，
            // 底下主界面的壁纸就会透出来，跟微信自带聊天背景叠在一起。
            runCatching {
                if (!ThemePages.isLauncher(activity)) return@runCatching
                TabWallpaperRenderer.hide(activity)
                // LauncherUI 退后台后重新回来可能是重建的，残留的页码会挂错图
                resetTabIndex()
            }.onFailure { WeLogger.d(TAG, "onActivityPaused 隐藏壁纸失败：${it.message}") }
        }

        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            windowBackups.remove(System.identityHashCode(activity))
            wallpaperAttached.remove(activity)
        }
    }

    fun registerCallbacks(register: Boolean) {
        try {
            val app = hostApp ?: return
            if (register && !callbacksRegistered) {
                app.registerActivityLifecycleCallbacks(activityCallbacks)
                callbacksRegistered = true
            } else if (!register && callbacksRegistered) {
                app.unregisterActivityLifecycleCallbacks(activityCallbacks)
                callbacksRegistered = false
            }
        } catch (e: Throwable) {
            WeLogger.w(TAG, "registerActivityLifecycleCallbacks 失败：${e.message}", e)
        }
    }

    // ══════════════════════════════════════════════════════════
    // 沉浸式系统栏（状态栏/导航栏透明 + 浅色图标，可恢复）
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

    /**
     * 注意：不设置 LAYOUT_FULLSCREEN/LAYOUT_STABLE 布局旗标 —— 那会让微信页面按全屏布局
     * 把内容顶到状态栏下（如「我 → 服务」页面整体上移被遮挡）。
     * 壁纸遮罩覆盖在 DecorView 全高（含系统栏区域），视觉上同样是全屏沉浸，且不改页面布局。
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
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = w.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } catch (e: Throwable) {
            WeLogger.w(TAG, "applyImmersive 异常：${e.message}", e)
        }
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
        } catch (e: Throwable) {
            WeLogger.w(TAG, "restoreWindow 异常：${e.message}", e)
        }
    }

    // ══════════════════════════════════════════════════════════
    // 挂载分发
    // ══════════════════════════════════════════════════════════

    /** 对全部存活页面重挂一次。 */
    fun applyToAllLive() {
        liveActivities().forEach { act ->
            try {
                applyTo(act)
            } catch (e: Throwable) {
                WeLogger.w(TAG, "应用主题到 ${act.javaClass.simpleName} 异常", e)
            }
        }
    }

    /**
     * 单个页面怎么挂：
     * - 扫一扫 / 看图页：任何壁纸都不挂（相机取景、照片会被遮挡）
     * - 聊天页：只允许「随机聊天背景」图池，主题壁纸一律不进来，同时把主界面那层藏起来
     * - 微信主界面：4 个 Tab 的分页壁纸
     * - 其它页面：不动；只有真的挂过才去还原
     */
    fun applyTo(activity: Activity) {
        if (ThemeStore.activeThemeIdOrEmpty().isEmpty()) return
        applyImmersive(activity)
        when {
            ThemePages.isScanner(activity) || ThemePages.isPhotoViewer(activity) -> removeWallpaper(activity)
            ThemePages.isChatting(activity) -> {
                hideLauncherWallpapers()
                applyChattingWallpaper(activity)
            }
            ThemePages.isLauncher(activity) -> applyTabWallpapers(activity)
            else -> {
                // 任何其它页面（设置页 / 朋友圈 / 小程序 / 扫码…）在前台时，
                // 主界面遮罩一律隐藏：主题只允许出现在主页/通讯录/发现/我 这 4 个 Tab 上。
                hideLauncherWallpapers()
                if (wallpaperAttached[activity] == true) removeWallpaper(activity)
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 4 个 Tab 的分页背景（写 DecorView 前景遮罩）
    // ══════════════════════════════════════════════════════════

    private val TAB_NAMES = listOf("主页", "通讯录", "发现", "我")

    /** 日志去重：同一组状态只打一次。 */
    @Volatile
    private var lastLoggedWallpaper: String? = null

    fun resetWallpaperLog() {
        lastLoggedWallpaper = null
    }

    /**
     * 主界面当前 Tab，三条路兜底（任一失败就退回上一条）：
     * 1. androidx ViewPager 的 `mCurItem` 字段（老写法）
     * 2. `getCurrentItem()` 方法（字段名漂移时仍可用）
     * 3. ViewPager2 的 `mCurrentItem` 字段 / `getCurrentItem()`
     *
     * 取到 0~3 以外的值一律当作「读不到」，用调用方给的默认页，绝不拿脏值去选图。
     */
    fun currentTabIndex(activity: Activity): Int =
        readRealTabIndex(activity) ?: lastTabIndex.takeIf { it in 0..3 } ?: 0

    /**
     * 真实页码；读不到返回 null（绝不返回 0 冒充）。
     *
     * 这条和 hook 是并行的两条路：hook 走微信回调（权威），这里走读 ViewPager 字段（兜底轮询用）。
     */
    fun readRealTabIndex(activity: Activity): Int? = runCatching {
        val content = activity.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
            ?: return@runCatching null
        val pagers = ArrayList<View>(2)
        findViewByClass(content, "androidx.viewpager.widget.ViewPager")?.let(pagers::add)
        findViewByClass(content, "androidx.viewpager2.widget.ViewPager2")?.let(pagers::add)
        if (pagers.isEmpty()) {
            warnPagerOnce("找不到 ViewPager/ViewPager2 控件")
            return@runCatching null
        }
        for (pager in pagers) {
            readPagerIndex(pager)?.let { if (it in 0..3) return@runCatching it }
        }
        warnPagerOnce("ViewPager 上 mCurItem/mCurrentItem/getCurrentItem 都取不到")
        null
    }.getOrNull()

    /** 页码读不到时只提醒一次，别刷日志。 */
    @Volatile private var pagerWarned = false
    private fun warnPagerOnce(reason: String) {
        if (pagerWarned) return
        pagerWarned = true
        WeLogger.w(TAG, "读不到主界面页码：$reason（切页换图将依赖 onPageSelected 回调）")
    }

    /** 页码读取是否成功过（诊断用）。 */
    fun pagerReadable(activity: Activity): Boolean = readRealTabIndex(activity) != null

    /** 读 ViewPager/ViewPager2 的当前页码：字段优先，方法兜底。 */
    private fun readPagerIndex(pager: View): Int? {
        runCatching {
            pager.reflekt().firstFieldOrNull { name = "mCurItem" }?.get()
                ?.let { (it as? Int)?.let { v -> return v } }
        }
        runCatching {
            pager.reflekt().firstFieldOrNull { name = "mCurrentItem" }?.get()
                ?.let { (it as? Int)?.let { v -> return v } }
        }
        return runCatching { pager.javaClass.getMethod("getCurrentItem").invoke(pager) as? Int }.getOrNull()
    }

    private fun findViewByClass(root: ViewGroup, className: String): View? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.javaClass.name == className) return child
            if (child is ViewGroup) findViewByClass(child, className)?.let { return it }
        }
        return null
    }

    /** tabIndex 对应的图片文件；该页没设就退回第一张有图的页。 */
    private fun fileForTab(files: List<java.io.File?>, tab: Int): java.io.File? =
        files.getOrNull(tab) ?: files.firstOrNull { it != null }

    /** 主界面 doOnCreate / 返回主界面时：挂一次 4 页背景（给 Feature 侧 hook 调用）。 */
    fun applyNow(activity: Activity) {
        runCatching { applyTabWallpapers(activity) }
            .onFailure { WeLogger.w(TAG, "挂载 4 页背景异常：${it.message}") }
    }

    /**
     * 主界面切 Tab（onPageScrolled 换页完成）时换一张图。
     *
     * 分页面壁纸（消息/通讯录/发现/我）只有在这里重挂才能「切过去就变」，
     * 否则要退出重进主界面才生效。
     */
    fun onTabScrolled(activity: Activity, position: Int, offset: Float) {
        if (offset != 0f || position == lastTabIndex) return
        lastTabIndex = position
        runCatching { updateTabWallpaper(activity, position) }
            .onFailure { WeLogger.w(TAG, "切 Tab 换背景失败：${it.message}") }
    }

    /**
     * 主界面切 Tab（ViewPager.onPageSelected，比 onPageScrolled 更可靠的一条信号）。
     *
     * 2026-10-27 之前只 hook 了 onPageScrolled + 只读 mCurItem，
     * 任一环取不到页码就会恒为 0 →「4 个页面永远显示主页那张图」。
     * 现在两条信号都接，页码取不到时还有 getCurrentItem()/ViewPager2 兜底。
     */
    fun onTabSelected(activity: Activity, position: Int) {
        if (position == lastTabIndex) return
        lastTabIndex = position
        runCatching { updateTabWallpaper(activity, position) }
            .onFailure { WeLogger.w(TAG, "切 Tab 换背景（onPageSelected）失败：${it.message}") }
    }

    /**
     * 挂 / 刷新 4 个页面的背景。
     *
     * 不往视图树塞任何节点（不占 index、不依赖自绘层布局），
     * 全部交给 [TabWallpaperRenderer] 写 DecorView 的 foreground。
     */
    private fun applyTabWallpapers(activity: Activity) {
        if (wallpaperFailStreak >= 3) return
        if (activity.window?.decorView == null) {
            WeLogger.w(TAG, "跳过挂载：decorView 不可用（${activity.javaClass.name}）")
            return
        }
        val activeId = ThemeStore.activeThemeIdOrEmpty()
        val files = runCatching { ThemeMaterials.tabWallpaperFiles(activeId) }.getOrDefault(emptyList())
        if (files.all { it == null }) {
            WeLogger.w(TAG, "跳过挂载：当前主题没有可用壁纸文件（active=$activeId）")
            TabWallpaperRenderer.clear(activity)
            return
        }
        // 以当前真实页码为准：LauncherUI 重建后 lastTabIndex 是上一次的残值，会挂错图
        val tab = currentTabIndex(activity).takeIf { it in 0..3 } ?: lastTabIndex
        if (tab in 0..3) lastTabIndex = tab
        // 页码取不到时兜底第一张可用图，宁可显示「主页」那张，也不要一片空白
        val file = fileForTab(files, tab) ?: files.firstOrNull { it != null }
        val alphaPct = ThemeStore.overlayOpacity()
        val result = TabWallpaperRenderer.apply(activity, file, tab, alphaPct)
        if (result != "ok") {
            wallpaperFailStreak += 1
            WeLogger.w(TAG, "挂载 4 页背景失败（第 $wallpaperFailStreak 次）：$result")
            return
        }
        wallpaperFailStreak = 0
        wallpaperAttached[activity] = true
        // 双保险：hook 接不上时靠轮询也能换页
        startTabPolling(activity)
        val info = files.mapIndexed { i, f -> "${TAB_NAMES[i]}=${f != null}" }.joinToString("，")
        val logKey = "$info|tab=$tab|${file?.absolutePath}"
        if (logKey != lastLoggedWallpaper) {
            lastLoggedWallpaper = logKey
            WeLogger.i(TAG, "4 页背景已写入前景遮罩（$info），Tab=$tab，文件=${file?.absolutePath ?: "null"}")
        }
    }

    /** 切 Tab：换一张图（同一套前景遮罩，只换 bitmap）。 */
    private fun updateTabWallpaper(activity: Activity, position: Int) {
        runCatching {
            if (wallpaperAttached[activity] != true) return@runCatching
            val files = ThemeMaterials.tabWallpaperFiles(ThemeStore.activeThemeIdOrEmpty())
            if (files.all { it == null }) return@runCatching
            val file = fileForTab(files, position) ?: files.firstOrNull { it != null }
            TabWallpaperRenderer.apply(activity, file, position, ThemeStore.overlayOpacity())
        }.onFailure { WeLogger.w(TAG, "切 Tab 换背景失败：${it.message}") }
    }

    // ══════════════════════════════════════════════════════════
    // 页码轮询：hook 万一没接上，也要能 4 页各归各页
    // ══════════════════════════════════════════════════════════

    private const val TAB_POLL_INTERVAL_MS = 400L

    private var tabPollAction: Runnable? = null
    private var tabPollTarget: ViewGroup? = null

    /**
     * 主界面在前台时每 400ms 对一次页码（只读一个 int + 比较，开销可忽略）。
     *
     * 有了它，即使 `onPageSelected` / `onPageScrolled` 一个都没 hook 上，
     * 切 Tab 后也能在一个轮询周期内换到正确的图。
     */
    private fun startTabPolling(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (tabPollTarget === decor && tabPollAction != null) return
        stopTabPolling()
        val action = object : Runnable {
            override fun run() {
                runCatching {
                    val idx = readRealTabIndex(activity)
                    if (idx != null && idx != lastTabIndex) {
                        lastTabIndex = idx
                        updateTabWallpaper(activity, idx)
                    }
                }
                decor.postDelayed(this, TAB_POLL_INTERVAL_MS)
            }
        }
        tabPollAction = action
        tabPollTarget = decor
        decor.postDelayed(action, TAB_POLL_INTERVAL_MS)
    }

    fun stopTabPolling() {
        val decor = tabPollTarget
        val action = tabPollAction
        tabPollTarget = null
        tabPollAction = null
        if (decor != null && action != null) runCatching { decor.removeCallbacks(action) }
    }

    /** 改透明度后重挂一次（前景遮罩的 alpha 每次 apply 重算）。 */
    fun refreshAlpha() {
        applyToAllLive()
    }

    /**
     * 把所有主界面（LauncherUI）的遮罩藏起来。
     *
     * 聊天页与很多二级页面是半透明窗口，藏晚了就会「主界面壁纸透过来」。
     * 隐藏态会写进 renderer 的 hidden 标记，定时保活不会再把它写回去。
     */
    fun hideLauncherWallpapers() {
        stopTabPolling()
        runCatching {
            liveActivities().forEach { act ->
                if (ThemePages.isLauncher(act)) TabWallpaperRenderer.hide(act)
            }
        }.onFailure { WeLogger.w(TAG, "隐藏主界面遮罩失败：${it.message}") }
    }

    /** 移除前景遮罩并还回窗口原来的 foreground。 */
    private fun removeTabWallpapers(activity: Activity) {
        runCatching {
            TabWallpaperRenderer.clear(activity)
            resetWallpaperLog()
        }.onFailure { WeLogger.w(TAG, "移除前景遮罩失败：${it.message}") }
    }

    // ══════════════════════════════════════════════════════════
    // 聊天页随机背景（只吃图池，绝不吃主题壁纸）
    // ══════════════════════════════════════════════════════════

    private val savedWindowBackgrounds =
        java.util.WeakHashMap<Activity, android.graphics.drawable.Drawable?>()

    private fun applyChattingWallpaper(activity: Activity) {
        val src = ThemeMaterials.wallpaperSourceFor(activity, ThemeStore.activeThemeIdOrEmpty())
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
            val pct = ThemeStore.overlayOpacity()
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

    /** 移除某个页面的全部主题痕迹。 */
    fun removeWallpaper(activity: Activity) {
        stopTabPolling()
        removeTabWallpapers(activity)
        runCatching {
            // 兼容历史版本留下的一次性壁纸层
            val decor = activity.window?.decorView as? ViewGroup ?: return@runCatching
            decor.findViewWithTag<View>("wcx_theme_store_wallpaper")?.let { old ->
                (old.parent as? ViewGroup)?.removeView(old)
            }
        }
        restoreWindowBackground(activity)
        wallpaperAttached.remove(activity)
    }

    /** 撤掉所有存活页面的壁纸与窗口还原。 */
    fun removeAllLive() {
        liveActivities().forEach { act ->
            runCatching { removeWallpaper(act) }
            runCatching { restoreWindow(act) }
        }
    }

    /**
     * 关掉「随机聊天背景」时立刻把已挂在聊天页上的壁纸撤掉，
     * 不用等用户退出再进一次聊天界面。
     */
    fun clearChattingWallpapers() {
        runCatching {
            liveActivities().filter { ThemePages.isChatting(it) }.forEach { removeWallpaper(it) }
        }.onFailure { WeLogger.w(TAG, "清除聊天页壁纸失败：${it.message}") }
    }

    // ══════════════════════════════════════════════════════════
    // 诊断
    // ══════════════════════════════════════════════════════════

    /** 单个页面的挂载现场（供主题诊断页）。 */
    fun describe(activity: Activity): String {
        val decor = activity.window?.decorView as? ViewGroup ?: return "  decorView 不可用"
        val content = runCatching { decor.findViewById<ViewGroup>(android.R.id.content) }.getOrNull()
        return buildString {
            appendLine("  decor=${decor.javaClass.simpleName} childCount=${decor.childCount} decorSize=${decor.width}x${decor.height}")
            appendLine(
                "  content=" + (content?.let { "${it.javaClass.simpleName} childCount=${it.childCount}" } ?: "未找到")
            )
            appendLine("  " + runCatching { TabWallpaperRenderer.describe(activity) }.getOrDefault("遮罩诊断异常"))
            appendLine("  wallpaperAttached=" + (wallpaperAttached[activity] == true))
        }
    }
}
