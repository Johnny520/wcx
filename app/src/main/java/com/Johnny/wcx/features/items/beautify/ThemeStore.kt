package com.Johnny.wcx.features.items.beautify

import android.view.View
import androidx.activity.ComponentActivity
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.activity.settings.SettingsNavigation
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.createDirsSafe
import java.io.File
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

/**
 * 主题工坊 —— 功能门面（2026-10-27 整体重写）。
 *
 * ## 这一层只做什么
 * 保留对外的**全部公开入口**（设置页调用的 18 个 API）、Feature 生命周期与「应用 / 恢复」这条主线；
 * 具体实现全部下沉到下面几个组件，互不纠缠：
 *
 * | 组件 | 职责 |
 * |---|---|
 * | [BuiltinThemes] / [ThemePaths] / [ThemeAliases] | 主题定义、素材路径、别名描述持久化 |
 * | [ThemeMaterials] | APK 素材提取、4 页壁纸解析、「当前页面该用哪张图」 |
 * | [ThemeBubbleKit] | 气泡备份 / 原子替换 / 9-patch 套用 |
 * | [ThemePages] / [ThemeSurface] | 页面分类、沉浸式窗口、生命周期挂载与还原 |
 * | [TabWallpaperRenderer] | DecorView 前景遮罩渲染（不塞子 View、不依赖布局、不拦点击） |
 *
 * **外观 / 入口 / 布局完全不变**：主题工坊页面仍是原来那套 UI，只是它背后调的东西换了。
 *
 * 历史：v245 起的六款内置主题（Hello Kitty / 小熊 / 线条小狗 / 吉伊卡哇 / 美食小狗 / 小兔子）、
 * 主题包导入导出、透明度 5~70%、气泡写入模块 assets、恢复默认自动还原备份 —— 全部保留。
 */
@Feature(
    name = "主题工坊",
    categories = ["界面美化"],
    description = "内置 6 款全局主题，全局沉浸壁纸（主页/通讯录/发现/我的 等 4 个界面，或全部界面）+ 对应气泡，支持透明度调节与备份恢复"
)
object ThemeStore : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    private const val TAG = "ThemeStore"

    private const val CUSTOM_BUBBLES_PREF_KEY = "自定义消息气泡"

    /** 当前功能名（与 @Feature.name 保持一致，初始化期 name 还没注入）。 */
    private const val NEW_FEATURE_NAME = "主题工坊"

    /** 改名前的老开关 key，用于把用户的旧状态迁过来。 */
    private const val LEGACY_FEATURE_NAME = "主题商城"

    init {
        // 功能从「主题商城」改名「主题工坊」。开关在 WePrefs 里以 name 为 key，
        // 这里只清掉旧 key，**不继承旧值** —— 主题总开关必须默认关闭，
        // 要开只能靠用户主动点「应用」（那时才写 true，否则应用完不生效）。
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

    /** 当前生效主题 id（内置主题与主题包共用）。 */
    private var activeThemeId by prefOption("theme_store_active_id", "")

    /** 壁纸透明度（%），5~70。 */
    private var overlayOpacityPct by prefOption("theme_store_overlay_opacity", 40)

    // ══════════════════════════════════════════════════════════
    // Feature 生命周期
    // ══════════════════════════════════════════════════════════

    override fun onEnable() {
        // 清掉历史遗留的持久化失败计数（老版本写进去的脏值会让主题被永久锁死）
        runCatching { WePrefs.remove("theme_store_wallpaper_fail") }
        ThemeSurface.hookTabSwitch()
        ThemeSurface.registerCallbacks(true)
        WeChatMessageViewApi.addListener(this)
        if (activeThemeId.isNotEmpty()) {
            // 进程重启后恢复：壁纸/沉浸式全局重挂（气泡文件已在 moduleAssets 持久存在）
            ThemeSurface.applyToAllLive()
            WeLogger.i(TAG, "已恢复主题：$activeThemeId")
        }
    }

    override fun onDisable() {
        restoreDefault()
        ThemeSurface.registerCallbacks(false)
        WeChatMessageViewApi.removeListener(this)
    }

    // ══════════════════════════════════════════════════════════
    // 入口
    // ══════════════════════════════════════════════════════════

    override fun onClick(context: ComponentActivity) {
        // 主题相关操作已全部并入「主题工坊」子页面，这里只做导航。
        SettingsNavigation.requestThemeWorkshop()
    }

    // ══════════════════════════════════════════════════════════
    // 应用主题
    // ══════════════════════════════════════════════════════════

    /**
     * 应用主题前确保相关 Feature 已启用。
     *
     * 曾经「点了内置主题没效果」的根因就在这里：只写了 activeThemeId，
     * 但「主题工坊」Feature 从未被开启 → hook 没装 → 页面上根本没人去画壁纸。
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
            FeaturesProvider.ALL_HOOK_ITEMS
                .filterIsInstance<SwitchFeature>()
                .firstOrNull { it.name == CUSTOM_BUBBLES_PREF_KEY }
                ?.takeIf { !it.isEnabled }
                ?.let {
                    WeLogger.i(TAG, "自定义消息气泡未启用，随「应用主题」自动开启")
                    WePrefs.putBool(CUSTOM_BUBBLES_PREF_KEY, true)
                    it.isEnabled = true
                }
        }.onFailure { WeLogger.w(TAG, "自动启用主题相关功能失败：${it.message}") }
    }

    /** 应用主题（内置或主题包），并同步一次壁纸透明度。 */
    fun applyThemeById(id: String) {
        // 用户主动应用 = 重新给一次机会，清掉本进程内的连续失败计数
        ThemeSurface.resetFailStreak()
        applyThemeInternal(id)
        setOverlayOpacity(overlayOpacityPct)
    }

    private fun applyThemeInternal(id: String) {
        ensureThemeFeaturesEnabled()
        // 外部主题包优先：壁纸与左右气泡取自包内素材，缺哪项就跳过，不影响其它维度。
        ThemePack.find(id)?.let {
            applyPackInternal(it)
            return
        }
        val theme = BuiltinThemes.find(id) ?: run {
            WeLogger.w(TAG, "未知主题：$id")
            return
        }
        try {
            ThemeMaterials.ensureExtracted(theme)
            ThemeBubbleKit.backupCurrent()
            // 气泡写入模块 assets（left=对方 / right=自己），并开启「自定义消息气泡」开关
            ThemeBubbleKit.replaceAtomic(
                listOfNotNull(
                    ThemePaths.bubbleFile(theme, ThemePaths.LEFT_BUBBLE_FILE)
                        .takeIf { it.exists() && it.toFile().length() > 0 }
                        ?.let { it to ThemePaths.activeBubble(ThemePaths.LEFT_BUBBLE_FILE) },
                    ThemePaths.bubbleFile(theme, ThemePaths.RIGHT_BUBBLE_FILE)
                        .takeIf { it.exists() && it.toFile().length() > 0 }
                        ?.let { it to ThemePaths.activeBubble(ThemePaths.RIGHT_BUBBLE_FILE) },
                )
            )
            WePrefs.putBool(CUSTOM_BUBBLES_PREF_KEY, true)
            activeThemeId = id
            // 切到内置主题时清掉主题包游标，避免上一套主题包继续生效
            ThemePack.currentId = ""
            ThemeSurface.applyToAllLive()
            WeLogger.i(TAG, "已应用主题：${theme.name}")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "applyThemeInternal 异常", e)
        }
    }

    /** 应用外部主题包（ThemePack）：壁纸 + 左右气泡。 */
    private fun applyPackInternal(pack: ThemePack.Pack) {
        try {
            val left = pack.bubbleOther()
            val right = pack.bubbleSelf()
            if (left != null || right != null) {
                ThemeBubbleKit.backupCurrent()
                // 原子替换：失败保留旧气泡；新主题没给的那一侧才删掉旧文件
                ThemeBubbleKit.replaceAtomic(
                    listOfNotNull(
                        left?.toPath()?.let { it to ThemePaths.activeBubble(ThemePaths.LEFT_BUBBLE_FILE) },
                        right?.toPath()?.let { it to ThemePaths.activeBubble(ThemePaths.RIGHT_BUBBLE_FILE) },
                    )
                )
                WePrefs.putBool(CUSTOM_BUBBLES_PREF_KEY, true)
            }
            activeThemeId = pack.id
            // 关键：壁纸读取走 ThemePack.active()（读 currentId）。
            // 只写 activeThemeId 不写 currentId，重启微信后壁纸永远解析不出来。
            ThemePack.currentId = pack.id
            ThemeSurface.applyToAllLive()
            WeLogger.i(
                TAG,
                "已应用主题包：${pack.name}（壁纸=${pack.wallpaper() != null}，气泡=${left != null}/${right != null}）"
            )
        } catch (e: Throwable) {
            WeLogger.e(TAG, "applyPackInternal 异常", e)
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
        val theme = BuiltinThemes.find(id) ?: run {
            WeLogger.w(TAG, "未知主题：$id")
            return null
        }
        val pack = ThemePack.createPackFromFiles(
            name = theme.name,
            wallpaper = ThemePaths.wallpaperFile(theme).takeIf { it.exists() }?.toFile(),
            bubbleLeft = ThemePaths.bubbleFile(theme, ThemePaths.LEFT_BUBBLE_FILE)
                .takeIf { it.exists() }?.toFile(),
            bubbleRight = ThemePaths.bubbleFile(theme, ThemePaths.RIGHT_BUBBLE_FILE)
                .takeIf { it.exists() }?.toFile(),
        ).getOrNull() ?: return null
        return ThemePack.exportZip(pack).getOrNull()
    }

    // ══════════════════════════════════════════════════════════
    // 恢复默认 / 清缓存
    // ══════════════════════════════════════════════════════════

    /** 恢复默认：清空主题 id、还原窗口与壁纸层、还原应用主题前的气泡文件。 */
    private fun restoreDefault() {
        try {
            ThemeBubbleKit.restoreBackup()
            activeThemeId = ""
            ThemePack.currentId = ""
            ThemeSurface.removeAllLive()
        } catch (e: Throwable) {
            WeLogger.e(TAG, "restoreDefault 异常", e)
        }
    }

    fun restoreDefaultTheme() = restoreDefault()

    /**
     * 清除主题相关缓存（应急用）。
     *
     * 场景：换主题后旧气泡没被替换、或没在用主题却还留着残留气泡时，
     * 一键把「模块目录里的气泡文件 + 内置素材提取缓存 + 已挂的背景」全部清掉，
     * 让微信回到原生样式。不会删用户的主题包本身，也不会动气泡备份。
     */
    fun clearThemeCache() {
        runCatching {
            ThemeBubbleKit.clearActive()
            // 内置素材提取缓存：下次应用主题会重新从 APK 提取
            runCatching {
                ThemePaths.builtinDir.toFile().deleteRecursively()
                ThemePaths.builtinDir.createDirsSafe()
            }
            ThemeSurface.resetWallpaperLog()
            ThemeSurface.resetFailStreak()
            TabWallpaperRenderer.invalidateCache()
            // 撤掉已经挂在页面上的背景
            ThemeSurface.removeAllLive()
            // 关掉气泡接管，避免它继续生效
            WePrefs.putBool(CUSTOM_BUBBLES_PREF_KEY, false)
            WeLogger.i(TAG, "已清除主题相关缓存（气泡 / 内置素材 / 背景层）")
        }.onFailure { WeLogger.w(TAG, "清除主题缓存失败：${it.message}") }
    }

    /**
     * 关掉「随机聊天背景」时，立刻把已经挂在聊天页上的壁纸撤掉，
     * 不用等用户退出再进一次聊天界面。
     */
    fun clearWallpaperOnChattingPages() = ThemeSurface.clearChattingWallpapers()

    // ══════════════════════════════════════════════════════════
    // 透明度
    // ══════════════════════════════════════════════════════════

    /** 壁纸透明度（5~70）。 */
    fun overlayOpacity(): Int = overlayOpacityPct.coerceIn(ThemeSurface.MIN_OPACITY, ThemeSurface.MAX_OPACITY)

    fun setOverlayOpacity(pct: Int) {
        overlayOpacityPct = pct.coerceIn(ThemeSurface.MIN_OPACITY, ThemeSurface.MAX_OPACITY)
        ThemeSurface.refreshAlpha()
    }

    // ══════════════════════════════════════════════════════════
    // 渲染方式（2026-10-27 重写后只有一种实现：DecorView 前景遮罩）
    // ══════════════════════════════════════════════════════════

    fun renderModeName(): String = "前景遮罩（压在内容上，不改页面结构）"

    /** 设置页常驻展示的遮罩状态（挂没挂上、多久之前挂的）。 */
    fun maskBrief(): String = runCatching {
        val at = TabWallpaperRenderer.lastAt
        if (at == 0L) "未挂载"
        else "已挂载 · ${((System.currentTimeMillis() - at) / 1000).toString()} 秒前"
    }.getOrDefault("未知")

    /** 设置页点「壁纸渲染方式」：立即重写一次前景遮罩。 */
    fun reapplyNow() {
        invalidateWallpaperCache()
        ThemeSurface.applyToAllLive()
        WeLogger.i(TAG, "手动重写前景遮罩：${TabWallpaperRenderer.lastResult}")
    }

    /** 换主题 / 改素材 / 改透明度后清图缓存，避免拿到旧图。 */
    fun invalidateWallpaperCache() {
        TabWallpaperRenderer.invalidateCache()
        ThemeSurface.resetWallpaperLog()
        ThemeSurface.resetFailStreak()
    }

    // ══════════════════════════════════════════════════════════
    // 供「主题工坊」页面调用的公开入口
    // ══════════════════════════════════════════════════════════

    /** 内置主题（id to 名称）。 */
    fun builtinThemeItems(): List<Pair<String, String>> = BuiltinThemes.all.map { it.id to it.name }

    /** 内置主题的自定义名称（长按重命名后存这里，为空则用内置原名）。 */
    fun builtinAlias(id: String): String = ThemeAliases.name(id)

    fun setBuiltinAlias(id: String, name: String) = ThemeAliases.setName(id, name)

    /** 内置主题的自定义描述（可选）。 */
    fun builtinDesc(id: String): String = ThemeAliases.desc(id)

    fun setBuiltinDesc(id: String, desc: String) = ThemeAliases.setDesc(id, desc)

    /** 内置主题展示名 = 自定义别名优先。 */
    fun builtinDisplayName(id: String, fallback: String): String = ThemeAliases.displayName(id, fallback)

    /** 默认（内置）主题的缩略图文件；首次调用会把素材从 APK 提取到模块数据目录。 */
    fun builtinThumbnail(id: String): File? = ThemeMaterials.thumbnail(id)

    /** 默认主题的主题色，壁纸缺失时用来兜底显示。 */
    fun builtinColor(id: String): Long = BuiltinThemes.previewColor(id)

    fun activeThemeIdOrEmpty(): String = activeThemeId

    // ══════════════════════════════════════════════════════════
    // 气泡：消息视图创建时按主题气泡替换背景（9-patch）
    // ══════════════════════════════════════════════════════════

    override fun onCreateView(
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
        view: View
    ) {
        if (activeThemeId.isEmpty()) return
        try {
            ThemeBubbleKit.applyToMessageView(param, view)
        } catch (e: Throwable) {
            WeLogger.w(TAG, "onCreateView 应用气泡异常", e)
        }
    }

    // ══════════════════════════════════════════════════════════
    // 诊断
    // ══════════════════════════════════════════════════════════

    /**
     * 主题全链路状态，供「主题诊断」一键复制。
     *
     * 每一行都对应链路上的一环，出现「没图 / 没生效」直接按行定位，不需要再猜。
     */
    fun diagnose(): String = buildString {
        appendLine("== 主题工坊诊断（${BuildConfig.VERSION_NAME}）==")
        appendLine(
            "功能开关 = " + runCatching { WePrefs.getBoolOrFalse(NEW_FEATURE_NAME) }.getOrDefault(false) +
                "（key=$NEW_FEATURE_NAME）"
        )
        appendLine("activeThemeId = '$activeThemeId'")
        appendLine("ThemePack.currentId = '" + runCatching { ThemePack.currentId }.getOrDefault("异常") + "'")
        appendLine(
            "ThemePack.active() = " + runCatching {
                ThemePack.active()?.let { pack -> "${pack.id} / ${pack.name}" } ?: "无"
            }.getOrDefault("异常")
        )
        appendLine("渲染方式 = ${renderModeName()}")
        appendLine("遮罩状态 = ${maskBrief()}")
        appendLine(
            "最近一次写入 = tab=${TabWallpaperRenderer.lastTab} 结果=${TabWallpaperRenderer.lastResult}"
        )
        appendLine("  文件=${TabWallpaperRenderer.lastPath}")
        appendLine("壁纸透明度 = ${overlayOpacity()}%")
        appendLine("自定义气泡开关 = " + runCatching { WePrefs.getBoolOrFalse(CUSTOM_BUBBLES_PREF_KEY) }.getOrDefault(false))
        appendLine(
            "气泡文件存在 = left:" + ThemePaths.activeBubble(ThemePaths.LEFT_BUBBLE_FILE).exists() +
                " right:" + ThemePaths.activeBubble(ThemePaths.RIGHT_BUBBLE_FILE).exists() +
                " 备份=" + ThemePaths.hasBubbleBackup()
        )
        appendLine("连续挂载失败计数 = ${ThemeSurface.wallpaperFailStreak}")

        val files = runCatching { ThemeMaterials.tabWallpaperFiles(activeThemeId) }.getOrDefault(emptyList())
        appendLine("4 页素材：")
        listOf("主页", "通讯录", "发现", "我").forEachIndexed { index, label ->
            val file = files.getOrNull(index)
            appendLine(
                "  $label = " + if (file == null) {
                    "缺失"
                } else {
                    "${file.absolutePath} (${runCatching { file.length() }.getOrDefault(0L)} B)"
                }
            )
        }

        val acts = runCatching { ThemeSurface.liveActivities() }.getOrDefault(emptyList())
        appendLine("存活 Activity = " + acts.joinToString("、") { it.javaClass.simpleName })
        acts.forEach { act ->
            appendLine("· ${act.javaClass.simpleName}")
            appendLine(runCatching { ThemeSurface.describe(act) }.getOrDefault("  诊断异常"))
        }
    }
}
